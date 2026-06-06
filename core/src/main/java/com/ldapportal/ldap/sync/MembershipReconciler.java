// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.Membership;
import com.ldapportal.entity.MembershipId;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.SyncScope;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.sync.identity.IdentityStrategy;
import com.ldapportal.ldap.sync.identity.IdentityStrategyRegistry;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MembershipReconciler {

    private final SyncSetRepository syncSetRepo;
    private final SyncLinkRepository syncLinkRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final IdentityStrategyRegistry identityStrategies;
    private final LdapConnectionFactory connectionFactory;
    private final MembershipRepository membershipRepo;
    private final RecomputeEngine engine;

    /** Strictly-increasing sweep generation, seeded above any stored epoch. */
    private final AtomicLong epochSequence = new AtomicLong(System.currentTimeMillis());

    /**
     * Reconcile one sync set synchronously. Returns the number of identities
     * enumerated at the source.
     */
    public int reconcile(UUID syncSetId) {
        SyncSet set = syncSetRepo.findById(syncSetId).orElse(null);
        if (set == null || !set.isEnabled()) {
            return 0;
        }
        SyncLink link = syncLinkRepo.findById(set.getLinkId()).orElse(null);
        if (link == null || !link.isEnabled()) {
            return 0;
        }
        DirectoryConnection source = directoryRepo.findById(link.getSourceDirId()).orElse(null);
        if (source == null) {
            return 0;
        }
        IdentityStrategy strategy = identityStrategies.forType(source.getDirectoryType());
        if (strategy.identityAttribute() == null) {
            log.warn("Sync set {}: source type has no LDAP identity attribute; reconcile skipped", syncSetId);
            return 0;
        }
        long epoch = epochSequence.incrementAndGet();

        // ── Enumerate the source scope ──
        List<Enumerated> enumerated = new ArrayList<>();
        boolean complete;
        try {
            enumerated = enumerateSource(source, set, strategy);
            complete = true;
        } catch (Exception ex) {
            log.warn("Sync set {}: source enumeration failed ({}); skipping not-seen sweep",
                    syncSetId, ex.toString());
            complete = false;
        }

        // ── Recompute each enumerated identity, stamp it as seen ──
        for (Enumerated e : enumerated) {
            engine.process(syncSetId, e.dn());
            stampSeen(syncSetId, e.identity(), epoch);
        }

        // ── Not-seen sweep (only after a complete scan) ──
        if (complete) {
            for (Membership row : membershipRepo.findNotSeen(syncSetId, epoch)) {
                // process(identity) re-reads the source by identity; still-present
                // entries stay, genuinely-absent ones are deleted.
                engine.process(syncSetId, row.getIdentity());
            }
        }
        return enumerated.size();
    }

    private void stampSeen(UUID syncSetId, String identity, long epoch) {
        membershipRepo.findById(new MembershipId(syncSetId, identity)).ifPresent(m -> {
            m.setLastScanEpoch(epoch);
            membershipRepo.save(m);
        });
    }

    private List<Enumerated> enumerateSource(DirectoryConnection source, SyncSet set,
                                             IdentityStrategy strategy) {
        String base = set.getObjectScopeBaseDn() != null ? set.getObjectScopeBaseDn() : source.getBaseDn();
        SearchScope scope = scopeOf(set);
        String idAttr = strategy.identityAttribute();
        return connectionFactory.withConnectionUnreplicated(source, conn -> {
            SearchRequest req = new SearchRequest(base, scope,
                    com.unboundid.ldap.sdk.Filter.createPresenceFilter("objectClass"), idAttr);
            List<Enumerated> out = new ArrayList<>();
            for (SearchResultEntry e : conn.search(req).getSearchEntries()) {
                String identity = strategy.extract(e);
                if (identity != null && !identity.isBlank()) {
                    out.add(new Enumerated(e.getDN(), identity));
                }
            }
            return out;
        });
    }

    private static SearchScope scopeOf(SyncSet set) {
        SyncScope s = set.getObjectScope() == null ? SyncScope.SUB : set.getObjectScope();
        return switch (s) {
            case BASE -> SearchScope.BASE;
            case ONE -> SearchScope.ONE;
            case SUB -> SearchScope.SUB;
        };
    }

    private record Enumerated(String dn, String identity) {
    }
}
