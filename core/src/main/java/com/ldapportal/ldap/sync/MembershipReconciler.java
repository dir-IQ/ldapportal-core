// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncReconcilePreview;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.Membership;
import com.ldapportal.entity.MembershipId;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.sync.identity.IdentityStrategy;
import com.ldapportal.ldap.sync.identity.IdentityStrategyRegistry;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Anti-entropy over the same membership function as the stream: a full reconcile
 * of a sync set enumerates the source, recomputes each in-scope identity, then
 * sweeps index rows it didn't see and recomputes those too (a re-read confirms
 * absence before any delete). Stream and reconcile can't disagree on selection —
 * only on latency — so reconcile is a consistency backstop for missed events,
 * gaps, and target drift, not the selection authority.
 *
 * <p>The not-seen sweep is gated behind a <em>complete</em> enumeration: a
 * partial scan must never mass-delete. Each swept row is additionally re-read
 * per-identity, so even a scan that silently dropped an entry can't delete a
 * still-present target.
 *
 * <h3>Delete safeguards</h3>
 * Before applying, a dry-run plan counts the deletions a reconcile would make
 * (scope-exit entries + not-seen rows). A <b>blast-radius / zero-enumeration
 * guard</b> then suppresses all deletes for the run — quarantining them for
 * REVIEW instead — when:
 * <ul>
 *   <li>the source returned <b>zero</b> entries while the set still manages some
 *       (a likely scope/filter/ACL misconfiguration), or</li>
 *   <li>the planned delete count exceeds an absolute cap, or</li>
 *   <li>it exceeds a percentage of the managed set (only above a population
 *       floor, so tiny sets aren't tripped by a single legitimate delete).</li>
 * </ul>
 * The same plan powers {@link #preview(UUID)} for the UI.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MembershipReconciler {

    /** Absolute ceiling on deletions per reconcile before the guard trips. */
    static final int DELETE_GUARD_MAX_ABSOLUTE = 50;
    /** Percentage of the managed set above which the guard trips… */
    static final int DELETE_GUARD_MAX_PERCENT = 20;
    /** …but only once the managed set is at least this large (avoids tripping a
     *  small set on one legitimate delete). */
    static final int DELETE_GUARD_MIN_POPULATION = 20;
    /** Cap on how many sample DNs the preview returns. */
    private static final int SAMPLE_DELETE_LIMIT = 25;

    private static final ReferenceResolver NO_RESOLVER = dn -> Optional.empty();

    private final SyncSetRepository syncSetRepo;
    private final SyncLinkRepository syncLinkRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final IdentityStrategyRegistry identityStrategies;
    private final LdapConnectionFactory connectionFactory;
    private final MembershipRepository membershipRepo;
    private final MembershipFunction membershipFunction;
    private final RecomputeEngine engine;

    /** Strictly-increasing sweep generation, seeded above any stored epoch. */
    private final AtomicLong epochSequence = new AtomicLong(System.currentTimeMillis());

    /**
     * Reconcile one sync set synchronously. Returns the number of identities
     * enumerated at the source.
     */
    public int reconcile(UUID syncSetId) {
        Context ctx = loadContext(syncSetId);
        if (ctx == null) {
            return 0;
        }
        Plan plan = computePlan(ctx);
        Guard guard = evaluateGuard(plan);

        long epoch = epochSequence.incrementAndGet();
        for (Planned p : plan.entries) {
            engine.process(syncSetId, p.entry.getDN(), guard.tripped());
            stampSeen(syncSetId, p.identity, epoch);
        }
        if (plan.complete) {
            for (Membership row : membershipRepo.findNotSeen(syncSetId, epoch)) {
                engine.process(syncSetId, row.getIdentity(), guard.tripped());
            }
        }
        if (guard.tripped()) {
            log.warn("Sync set {}: delete guard TRIPPED — {} planned deletes suppressed "
                    + "(held for review): {}", syncSetId, plan.deleteCandidates.size(), guard.reason());
        }
        ctx.set.setReconcileLastRunAt(java.time.OffsetDateTime.now());
        syncSetRepo.save(ctx.set);
        return plan.entries.size();
    }

    /** Dry-run: what a reconcile would do, computed without writing to the target. */
    public SyncReconcilePreview preview(UUID syncSetId) {
        Context ctx = loadContext(syncSetId);
        if (ctx == null) {
            return new SyncReconcilePreview(0, 0, 0, 0, List.of(), false,
                    "Sync set, link, or source/target directory is unavailable or disabled.", false);
        }
        Plan plan = computePlan(ctx);
        Guard guard = evaluateGuard(plan);
        List<String> sample = plan.deleteCandidates.stream()
                .map(Membership::getTargetDn)
                .filter(dn -> dn != null && !dn.isBlank())
                .limit(SAMPLE_DELETE_LIMIT)
                .toList();
        return new SyncReconcilePreview(plan.entries.size(), plan.managedCount, plan.adds,
                plan.deleteCandidates.size(), sample, guard.tripped(), guard.reason(), plan.complete);
    }

    // ── Planning (no writes) ───────────────────────────────────────────────────

    private Plan computePlan(Context ctx) {
        List<Planned> entries = new ArrayList<>();
        boolean complete;
        try {
            for (SearchResultEntry e : enumerateSource(ctx)) {
                var decision = membershipFunction.evaluate(ctx.set, ctx.strategy, e, NO_RESOLVER);
                String identity = decision.identity();
                if (identity != null && !identity.isBlank()) {
                    entries.add(new Planned(e, identity, decision.member()));
                }
            }
            complete = true;
        } catch (Exception ex) {
            log.warn("Sync set {}: source enumeration failed ({}); skipping not-seen sweep",
                    ctx.set.getId(), ex.toString());
            complete = false;
        }

        List<Membership> managed = membershipRepo.findAllBySyncSetId(ctx.set.getId());
        Map<String, Membership> byIdentity = new HashMap<>();
        for (Membership m : managed) {
            byIdentity.put(m.getIdentity(), m);
        }
        Set<String> seen = new HashSet<>();
        int adds = 0;
        List<Membership> deletes = new ArrayList<>();
        for (Planned p : entries) {
            seen.add(p.identity);
            Membership row = byIdentity.get(p.identity);
            if (p.member) {
                if (row == null || row.getState() != MembershipState.APPLIED) {
                    adds++;
                }
            } else if (row != null && row.getState() == MembershipState.APPLIED) {
                deletes.add(row); // enumerated but now out of scope
            }
        }
        if (complete) {
            for (Membership row : managed) {
                if (row.getState() == MembershipState.APPLIED && !seen.contains(row.getIdentity())) {
                    deletes.add(row); // not seen this scan
                }
            }
        }
        return new Plan(entries, managed.size(), adds, deletes, complete);
    }

    private Guard evaluateGuard(Plan plan) {
        return guardFor(plan.complete, plan.entries.size(), plan.managedCount,
                plan.deleteCandidates.size());
    }

    /**
     * Pure blast-radius decision (package-private for direct unit testing).
     * Suppress all deletes this run when the source came back empty while the set
     * still manages entries, when the planned deletes exceed the absolute cap, or
     * when they exceed a percentage of a large-enough managed set.
     */
    static Guard guardFor(boolean complete, int enumeratedCount, long managedCount, int deleteCount) {
        if (!complete) {
            return Guard.ok(); // incomplete scan ⇒ sweep skipped, nothing to suppress
        }
        if (managedCount > 0 && enumeratedCount == 0) {
            return Guard.trip("source returned no entries while " + managedCount
                    + " are managed — likely a scope/filter/ACL misconfiguration");
        }
        if (deleteCount > DELETE_GUARD_MAX_ABSOLUTE) {
            return Guard.trip("planned deletions (" + deleteCount + ") exceed the absolute cap of "
                    + DELETE_GUARD_MAX_ABSOLUTE);
        }
        if (managedCount >= DELETE_GUARD_MIN_POPULATION
                && (long) deleteCount * 100 > managedCount * DELETE_GUARD_MAX_PERCENT) {
            return Guard.trip("planned deletions (" + deleteCount + ") exceed " + DELETE_GUARD_MAX_PERCENT
                    + "% of " + managedCount + " managed entries");
        }
        return Guard.ok();
    }

    private Context loadContext(UUID syncSetId) {
        SyncSet set = syncSetRepo.findById(syncSetId).orElse(null);
        if (set == null || !set.isEnabled()) {
            return null;
        }
        SyncLink link = syncLinkRepo.findById(set.getLinkId()).orElse(null);
        if (link == null || !link.isEnabled()) {
            return null;
        }
        DirectoryConnection source = directoryRepo.findById(link.getSourceDirId()).orElse(null);
        if (source == null) {
            return null;
        }
        IdentityStrategy strategy = identityStrategies.forType(source.getDirectoryType());
        if (SyncIdentity.attribute(set, strategy) == null) {
            log.warn("Sync set {}: source type has no LDAP identity attribute; reconcile skipped", syncSetId);
            return null;
        }
        return new Context(set, link, source, strategy);
    }

    private void stampSeen(UUID syncSetId, String identity, long epoch) {
        membershipRepo.findById(new MembershipId(syncSetId, identity)).ifPresent(m -> {
            m.setLastScanEpoch(epoch);
            membershipRepo.save(m);
        });
    }

    private List<SearchResultEntry> enumerateSource(Context ctx) {
        String base = ctx.set.getObjectScopeBaseDn() != null
                ? ctx.set.getObjectScopeBaseDn() : ctx.source.getBaseDn();
        SearchScope scope = SyncScopes.searchScope(ctx.set);
        String idAttr = SyncIdentity.attribute(ctx.set, ctx.strategy);
        return connectionFactory.withConnectionUnreplicated(ctx.source, conn -> {
            // Fetch user attributes ("*") so membership can be evaluated for the
            // plan, plus the identity attribute explicitly (it may be operational,
            // e.g. entryUUID, which "*" does not return).
            SearchRequest req = new SearchRequest(base, scope,
                    com.unboundid.ldap.sdk.Filter.createPresenceFilter("objectClass"), "*", idAttr);
            return new ArrayList<>(conn.search(req).getSearchEntries());
        });
    }

    private record Context(SyncSet set, SyncLink link, DirectoryConnection source, IdentityStrategy strategy) {
    }

    private record Planned(Entry entry, String identity, boolean member) {
    }

    private record Plan(List<Planned> entries, long managedCount, int adds,
                        List<Membership> deleteCandidates, boolean complete) {
    }

    record Guard(boolean tripped, String reason) {
        static Guard ok() {
            return new Guard(false, null);
        }

        static Guard trip(String reason) {
            return new Guard(true, reason);
        }
    }
}
