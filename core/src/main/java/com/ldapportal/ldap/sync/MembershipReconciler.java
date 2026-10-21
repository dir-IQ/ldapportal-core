// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncReconcilePreview;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.Membership;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.exception.ConflictException;
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

import java.time.Duration;
import java.time.OffsetDateTime;
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
 *
 * <p>A reconcile is single-flight per set across app instances: it claims the
 * set's reconcile lease first and heartbeats it while it runs, so instances whose
 * schedulers find the same set due skip it, and a manual run while one is
 * running is refused ({@link ConflictException}).
 *
 * <p>Every run is recorded in the set's reconcile history
 * ({@link SyncReconcileHistory}): its trigger, the plan's shape, and what the
 * engine did to each entry.
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

    /** A reconcile lease silent this long belonged to a server that stopped; another run may claim it. */
    static final Duration LEASE_STALE = Duration.ofMinutes(15);
    /** How often a running reconcile refreshes its lease. */
    static final Duration LEASE_HEARTBEAT = Duration.ofSeconds(30);

    private final SyncSetRepository syncSetRepo;
    private final SyncLinkRepository syncLinkRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final IdentityStrategyRegistry identityStrategies;
    private final LdapConnectionFactory connectionFactory;
    private final MembershipRepository membershipRepo;
    private final MembershipFunction membershipFunction;
    private final RecomputeEngine engine;
    private final SyncReconcileHistory history;

    /** Strictly-increasing sweep generation, seeded above any stored epoch. */
    private final AtomicLong epochSequence = new AtomicLong(System.currentTimeMillis());

    /** Reconcile one sync set synchronously, recorded as a manual run with no requester. */
    public int reconcile(UUID syncSetId) {
        return reconcile(syncSetId, SyncReconcileTrigger.MANUAL, null);
    }

    /**
     * Reconcile one sync set synchronously, recording the run in the set's
     * history. Returns the number of identities enumerated at the source. A
     * disabled or unavailable set is skipped and not recorded.
     *
     * @param requestedBy the operator, for a manual run
     * @throws ConflictException when another reconcile of the set is running
     *         (on this or another app instance)
     */
    public int reconcile(UUID syncSetId, SyncReconcileTrigger trigger, String requestedBy) {
        Lease lease = new Lease(syncSetId);
        if (!lease.claim()) {
            throw new ConflictException("A reconcile of this sync set is already running.");
        }
        try {
            // Loaded after the claim, so the request counter it records covers
            // everything raised before this run began.
            Context ctx = loadContext(syncSetId, true);
            if (ctx == null) {
                return 0;
            }
            SyncReconcileHistory.Run run = history.start(syncSetId, trigger, requestedBy);
            try {
                int enumerated = reconcile(ctx, run, lease);
                history.complete(run, membershipRepo.countBySyncSetId(syncSetId));
                return enumerated;
            } catch (RuntimeException ex) {
                history.fail(run, ex);
                throw ex;
            }
        } finally {
            lease.release();
        }
    }

    private int reconcile(Context ctx, SyncReconcileHistory.Run run, Lease lease) {
        UUID syncSetId = ctx.set.getId();
        Plan plan = computePlan(ctx);
        Guard guard = evaluateGuard(plan);
        lease.beat(); // the source scan can be long
        history.planned(run, plan.entries.size(), plan.managedCount, plan.complete,
                guard.tripped(), guard.reason());

        long epoch = epochSequence.incrementAndGet();
        int faults = 0;
        for (Planned p : plan.entries) {
            // Per-entry isolation: one entry whose recompute faults (a source read
            // that throws, a contended index row) must not abort the run and skip
            // the sweep for everyone else. It is still stamped as seen — it was
            // enumerated, so it must not become a not-seen delete candidate.
            lease.beat();
            try {
                history.record(run, engine.process(syncSetId, p.entry.getDN(), guard.tripped()));
            } catch (Exception ex) {
                faults++;
                history.fault(run);
                log.warn("Sync set {}: reconcile recompute of {} faulted ({}); continuing",
                        syncSetId, p.entry.getDN(), ex.toString());
            } finally {
                stampSeen(syncSetId, p.identity, epoch);
            }
        }
        if (plan.complete) {
            for (Membership row : membershipRepo.findNotSeen(syncSetId, epoch)) {
                lease.beat();
                try {
                    history.record(run, engine.process(syncSetId, row.getIdentity(), guard.tripped()));
                } catch (Exception ex) {
                    faults++;
                    history.fault(run);
                    log.warn("Sync set {}: reconcile sweep of identity {} faulted ({}); continuing",
                            syncSetId, row.getIdentity(), ex.toString());
                }
            }
        }
        if (faults > 0) {
            log.warn("Sync set {}: reconcile finished with {} faulted recompute(s); they retry next run",
                    syncSetId, faults);
        }
        if (guard.tripped()) {
            log.warn("Sync set {}: delete guard TRIPPED — {} planned deletes suppressed "
                    + "(held for review): {}", syncSetId, plan.deleteCandidates.size(), guard.reason());
        }
        // A targeted update, not a save of ctx.set: the set may have been edited
        // while this run worked, and its stale version made the save fail.
        syncSetRepo.stampReconcileRun(syncSetId, OffsetDateTime.now());
        // ctx.set was loaded before the enumeration started, so its request
        // counter is exactly what this run covers; anything raised since stays due.
        // An incomplete enumeration didn't cover every entry, so it only counts as
        // an attempt and the request is retried.
        syncSetRepo.markReconcileRun(syncSetId, ctx.set.getReconcileRequestSeq(), plan.complete);
        return plan.entries.size();
    }

    /** Dry-run: what a reconcile would do, computed without writing to the target. */
    public SyncReconcilePreview preview(UUID syncSetId) {
        // A preview never writes, so it plans for a disabled set or link too —
        // that is exactly when an operator needs it: before enabling (the UI's
        // enable confirmation runs this and asks only if deletes are planned).
        Context ctx = loadContext(syncSetId, false);
        if (ctx == null) {
            return new SyncReconcilePreview(0, 0, 0, 0, List.of(), false,
                    "Sync set, link, or source directory is unavailable.", false);
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
            } else if (row != null) {
                // Any tracked state: the engine's OUT path deletes for FAILED and
                // PENDING rows too, so the guard must count what it would do.
                deletes.add(row); // enumerated but now out of scope
            }
        }
        if (complete) {
            for (Membership row : managed) {
                if (!seen.contains(row.getIdentity())) {
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

    /**
     * @param requireEnabled true for anything that writes (reconcile): a disabled
     *                       set or link is skipped. A read-only preview passes
     *                       false so it can plan before the set is turned on.
     */
    private Context loadContext(UUID syncSetId, boolean requireEnabled) {
        SyncSet set = syncSetRepo.findById(syncSetId).orElse(null);
        if (set == null || (requireEnabled && !set.isEnabled())) {
            return null;
        }
        SyncLink link = syncLinkRepo.findById(set.getLinkId()).orElse(null);
        if (link == null || (requireEnabled && !link.isEnabled())) {
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

    /**
     * A targeted column update, not an entity save: the engine (worker thread, or
     * another instance) may be writing the same versioned row concurrently, and an
     * optimistic-lock failure here used to abort the whole reconcile.
     */
    private void stampSeen(UUID syncSetId, String identity, long epoch) {
        try {
            membershipRepo.stampScanEpoch(syncSetId, identity, epoch);
        } catch (Exception ex) {
            // Worst case the row looks not-seen and is re-read per identity before
            // any delete, so a failed stamp can only cost work, never an entry.
            log.debug("Sync set {}: could not stamp scan epoch on {}: {}", syncSetId, identity, ex.toString());
        }
    }

    private List<SearchResultEntry> enumerateSource(Context ctx) {
        return enumerate(ctx.set, ctx.source, ctx.strategy);
    }

    /**
     * The source scan a reconcile plans from: every entry in the set's scope,
     * paged. Package-private so the per-entry preview enumerates identically.
     */
    List<SearchResultEntry> enumerate(SyncSet set, DirectoryConnection source, IdentityStrategy strategy) {
        String base = set.getObjectScopeBaseDn() != null ? set.getObjectScopeBaseDn() : source.getBaseDn();
        SearchScope scope = SyncScopes.searchScope(set);
        String idAttr = SyncIdentity.attribute(set, strategy);
        int pageSize = source.getPagingSize();
        return connectionFactory.withConnectionUnreplicated(source, conn -> {
            // Fetch user attributes ("*") so membership can be evaluated for the
            // plan, plus the identity attribute explicitly (it may be operational,
            // e.g. entryUUID, which "*" does not return). Paged, so a scope larger
            // than the server's size limit is still enumerated completely — the
            // not-seen sweep is gated on completeness, and an unpaged search that
            // tripped the limit made every reconcile of a large scope a no-op.
            SearchRequest req = new SearchRequest(base, scope,
                    com.unboundid.ldap.sdk.Filter.createPresenceFilter("objectClass"), "*", idAttr);
            return SyncPagedSearch.all(conn, req, pageSize);
        });
    }

    private record Context(SyncSet set, SyncLink link, DirectoryConnection source, IdentityStrategy strategy) {
    }

    /** One run's hold on the set's reconcile lease. */
    private final class Lease {
        private final UUID syncSetId;
        private final UUID token = UUID.randomUUID();
        private OffsetDateTime lastBeat;

        Lease(UUID syncSetId) {
            this.syncSetId = syncSetId;
        }

        boolean claim() {
            OffsetDateTime now = OffsetDateTime.now();
            lastBeat = now;
            return syncSetRepo.claimReconcile(syncSetId, token, now, now.minus(LEASE_STALE)) == 1;
        }

        /**
         * Refresh the lease when the heartbeat is due. If another run reclaimed it
         * (this one went silent past {@link #LEASE_STALE}), stop rather than run
         * alongside it.
         */
        void beat() {
            OffsetDateTime now = OffsetDateTime.now();
            if (lastBeat.plus(LEASE_HEARTBEAT).isAfter(now)) {
                return;
            }
            if (syncSetRepo.heartbeatReconcile(syncSetId, token, now) == 0) {
                throw new IllegalStateException("Another reconcile of this sync set took over after this one "
                        + "stopped heartbeating; this run stopped.");
            }
            lastBeat = now;
        }

        void release() {
            try {
                syncSetRepo.releaseReconcile(syncSetId, token);
            } catch (Exception ex) {
                // Worst case the lease lapses after LEASE_STALE and the next run claims it.
                log.warn("Sync set {}: could not release the reconcile lease: {}", syncSetId, ex.toString());
            }
        }
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
