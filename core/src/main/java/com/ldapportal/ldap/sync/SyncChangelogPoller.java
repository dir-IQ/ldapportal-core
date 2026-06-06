// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.SyncChangelogHealth;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.changelog.ChangelogReadContext;
import com.ldapportal.ldap.changelog.ChangelogStrategy;
import com.ldapportal.ldap.changelog.DseeChangelogStrategy;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.RootDSE;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Changelog-capture adapter: polls each CHANGELOG-mode sync link's source
 * changelog and emits {@code recompute(targetDN)} per change record — including
 * DELETE records (the engine re-reads the source → absent → OUT → deletes via
 * the index, needing no source attrs). This is the design's key simplification:
 * the changelog's lossy {@code changes} blob is ignored, so there is no
 * exactly-once dedup, no per-link FIFO, and no LDIF reconstruction — convergence
 * makes a bare "this DN changed" signal sufficient.
 *
 * <p>Keeps the operational machinery: an HA poll-lease, a cursor, lag/gap/cursor-
 * reset health, and a reconcile trigger when the cursor falls off the bottom of
 * the changelog. Gated on {@code DIRECTORY_SYNC}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncChangelogPoller {

    private static final int BATCH = 500;
    private static final long LAG_THRESHOLD = 1000;
    private static final Duration STALE_LEASE = Duration.ofMinutes(5);
    private static final DseeChangelogStrategy DSEE = new DseeChangelogStrategy();

    private final SyncLinkRepository linkRepo;
    private final SyncSetRepository syncSetRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final LdapConnectionFactory connectionFactory;
    private final RecomputeEnqueuer enqueuer;
    private final MembershipReconciler reconciler;
    private final EntitlementService entitlementService;

    @Scheduled(fixedDelayString = "${ldapportal.sync.changelog.fixed-delay-ms:15000}")
    public void poll() {
        if (!entitlementService.has(Entitlement.DIRECTORY_SYNC)) {
            return;
        }
        try {
            OffsetDateTime now = OffsetDateTime.now();
            OffsetDateTime staleBefore = now.minus(STALE_LEASE);
            for (UUID linkId : linkRepo.findChangelogCaptureLinkIds()) {
                if (linkRepo.claimChangelogPoll(linkId, now, staleBefore) == 0) {
                    continue; // another instance holds the lease
                }
                pollOne(linkId);
            }
        } catch (Exception ex) {
            log.error("Sync changelog poll pass failed: {}", ex.toString());
        }
    }

    /** Poll one link (visible for tests; the scheduled path gates + leases first). */
    void pollOne(UUID linkId) {
        SyncLink link = linkRepo.findById(linkId).orElse(null);
        if (link == null) {
            return;
        }
        DirectoryConnection source = directoryRepo.findById(link.getSourceDirId()).orElse(null);
        ChangelogStrategy strategy = strategyFor(link.getChangelogFormat());
        if (source == null || strategy == null) {
            markError(link, "source directory or changelog strategy unavailable",
                    SyncChangelogHealth.DISABLED_CONFIG_ERROR);
            return;
        }
        List<SyncSet> sets = syncSetRepo.findAllByLinkId(linkId).stream().filter(SyncSet::isEnabled).toList();
        try {
            PollResult r = connectionFactory.withConnectionUnreplicated(source, conn -> {
                RootDSE root = conn.getRootDSE();
                Long firstCn = root == null ? null : root.getAttributeValueAsLong("firstChangeNumber");
                Long lastCn = root == null ? null : root.getAttributeValueAsLong("lastChangeNumber");
                Long cursor = link.getChangelogLastChangeNumber();

                boolean cursorReset = cursor != null && lastCn != null && lastCn < cursor;
                if (cursorReset) {
                    cursor = 0L;
                }
                boolean gap = cursor != null && firstCn != null && firstCn > cursor + 1;

                ChangelogReadContext ctx = new ChangelogReadContext(link.getChangelogBaseDn(), null, cursor);
                SearchRequest req = strategy.buildSearchRequest(ctx, BATCH);
                long maxCn = cursor != null ? cursor : 0L;
                for (SearchResultEntry e : conn.search(req).getSearchEntries()) {
                    String id = strategy.extractEntryId(e);
                    String dn = strategy.extractTargetDn(e);
                    if (id == null || dn == null) {
                        continue;
                    }
                    long cn = Long.parseLong(id);
                    for (SyncSet set : sets) {
                        if (inScope(set, dn)) {
                            enqueuer.enqueue(set.getId(), dn, cn);
                        }
                    }
                    maxCn = Math.max(maxCn, cn);
                }
                return new PollResult(maxCn, lastCn, gap, cursorReset);
            });

            if (r.gap()) {
                log.warn("Sync link {}: changelog gap detected; reconciling to re-derive state", linkId);
                for (SyncSet set : sets) {
                    reconciler.reconcile(set.getId());
                }
            }
            advance(link, r);
        } catch (Exception ex) {
            markError(link, ex.getMessage(), SyncChangelogHealth.STALLED);
        }
    }

    private void advance(SyncLink link, PollResult r) {
        long newCursor = r.gap() && r.sourceHead() != null ? r.sourceHead() : r.maxChangeNumber();
        link.setChangelogLastChangeNumber(newCursor);
        link.setChangelogSourceLastChangeNumber(r.sourceHead());
        link.setChangelogLastPolledAt(OffsetDateTime.now());
        link.setChangelogLastError(null);
        link.setChangelogPollClaimedAt(null);
        SyncChangelogHealth health = SyncChangelogHealth.HEALTHY;
        if (r.cursorReset()) {
            health = SyncChangelogHealth.CURSOR_RESET;
        } else if (r.gap()) {
            health = SyncChangelogHealth.GAP_DETECTED;
        } else if (r.sourceHead() != null && r.sourceHead() - newCursor > LAG_THRESHOLD) {
            health = SyncChangelogHealth.LAGGING;
        }
        link.setChangelogHealth(health);
        linkRepo.save(link);
    }

    private void markError(SyncLink link, String message, SyncChangelogHealth health) {
        link.setChangelogLastError(message);
        link.setChangelogLastErrorAt(OffsetDateTime.now());
        link.setChangelogHealth(health);
        link.setChangelogPollClaimedAt(null);
        linkRepo.save(link);
        log.warn("Sync link {}: changelog poll error ({}): {}", link.getId(), health, message);
    }

    private static ChangelogStrategy strategyFor(ChangelogFormat format) {
        return format == ChangelogFormat.DSEE_CHANGELOG ? DSEE : null;
    }

    private static boolean inScope(SyncSet set, String dn) {
        String base = set.getObjectScopeBaseDn();
        if (base == null) {
            return true;
        }
        try {
            return new DN(dn).isDescendantOf(new DN(base), true);
        } catch (LDAPException ex) {
            return false;
        }
    }

    private record PollResult(long maxChangeNumber, Long sourceHead, boolean gap, boolean cursorReset) {
    }
}
