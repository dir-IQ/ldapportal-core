// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.observability.CorrelationContext;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.changelog.AccesslogStrategy;
import com.ldapportal.ldap.changelog.ChangelogChange;
import com.ldapportal.ldap.changelog.ChangelogParseException;
import com.ldapportal.ldap.changelog.ChangelogReadContext;
import com.ldapportal.ldap.changelog.ChangelogStrategy;
import com.ldapportal.ldap.changelog.DirSyncChangelogStrategy;
import com.ldapportal.ldap.changelog.DseeChangelogStrategy;
import com.ldapportal.ldap.replication.ChangelogPollTxOps.ClaimedPoll;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.RootDSE;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scheduled poller for CHANGELOG-capture links (design §6). For each enabled
 * CHANGELOG link it claims a DB lease, reads the source directory's external
 * changelog from a cursor, reconstructs each entry into a
 * {@code PendingReplicationEvent} via {@link ChangelogStrategy#extractChange}
 * + {@link DnMapper} + {@link ReplicationPayloadMapper}, and hands them to the
 * existing dispatch queue — closing the out-of-band write gap that
 * {@code APP_INTERCEPT} can't see.
 *
 * <p>Runs non-transactionally like {@code ReplicationWorker}; all DB mutations
 * go through {@link ChangelogPollTxOps}. Per-link polls run on a small bounded
 * pool so a long catch-up on one link doesn't block the others, and a DB lease
 * keeps each link single-flight across the pool and across instances.
 *
 * <p><b>Exactly-once:</b> events carry their source {@code changeNumber}; a
 * pre-check against {@link ReplicationEventRepository#findExistingChangelogNumbers}
 * plus the partial unique index make persist-then-advance safe under crash
 * replay (§6.5). <b>Cursor:</b> CAS-advanced to the highest changeNumber
 * processed, so a mid-poll failure replays only a bounded, de-duplicated prefix.
 *
 * <p>Gap / cursor-reset detection, poison dead-lettering, the health state
 * machine, and the exclude filter are the C3R / C3X hardening layers; this
 * class is the C3 core. On a parse failure it logs and skips past the entry
 * (reconciliation re-derives it) rather than wedging the link.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReplicationChangelogPoller {

    private final ReplicationLinkRepository  linkRepo;
    private final ReplicationEventRepository eventRepo;
    private final ReplicationReadOps         readOps;
    private final ReplicationEventPersister  persister;
    private final LdapConnectionFactory      connectionFactory;
    private final ChangelogPollTxOps         txOps;
    /** May be null in direct-construction unit tests → entitlement gate treated as open. */
    private final EntitlementService         entitlementService;

    @Value("${ldapportal.replication.changelog.pool-size:2}")
    private int poolSize;
    @Value("${ldapportal.replication.changelog.max-per-poll:500}")
    private int maxPerPoll;
    /** A poll lease older than this is presumed orphaned by a crash. */
    @Value("${ldapportal.replication.changelog.lease-timeout-ms:300000}")
    private long leaseTimeoutMs;

    private ExecutorService executor;

    // Stateless, reusable strategy instances (mirrors LdapChangelogReader).
    private static final DseeChangelogStrategy    DSEE      = new DseeChangelogStrategy();
    private static final AccesslogStrategy        ACCESSLOG = new AccesslogStrategy();
    private static final DirSyncChangelogStrategy DIRSYNC   = new DirSyncChangelogStrategy();

    private static ChangelogStrategy strategyFor(ChangelogFormat format) {
        return switch (format) {
            case DSEE_CHANGELOG     -> DSEE;
            case OPENLDAP_ACCESSLOG -> ACCESSLOG;
            case AD_DIRSYNC         -> DIRSYNC;
        };
    }

    @PostConstruct
    void initExecutor() {
        executor = Executors.newFixedThreadPool(Math.max(1, poolSize), r -> {
            Thread t = new Thread(r, "changelog-poller");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdownExecutor() {
        if (executor != null) executor.shutdownNow();
    }

    // ── Schedules ───────────────────────────────────────────────────────────────

    @Scheduled(
            fixedDelayString   = "${ldapportal.replication.changelog.poll-ms:30000}",
            initialDelayString = "${ldapportal.replication.changelog.initial-delay-ms:20000}")
    public void pollAll() {
        try {
            // Edition gate, mirroring ReplicationEnqueuer / ReconciliationScheduler:
            // a commercial → community downgrade must stop autonomous capture.
            if (entitlementService != null && !entitlementService.has(Entitlement.DIRECTORY_SYNC)) {
                return;
            }
            for (UUID linkId : linkRepo.findChangelogCaptureLinkIds()) {
                executor.submit(() -> pollLinkSafely(linkId));
            }
        } catch (RuntimeException ex) {
            // A @Scheduled method that throws stops being rescheduled — swallow.
            log.error("Changelog poll sweep failed: {}", ex.toString());
        }
    }

    @Scheduled(fixedDelayString = "${ldapportal.replication.changelog.stale-sweep-ms:120000}")
    void resetStaleLeases() {
        try {
            OffsetDateTime threshold = OffsetDateTime.now().minus(Duration.ofMillis(leaseTimeoutMs));
            int reset = txOps.resetStaleLeases(threshold);
            if (reset > 0) log.warn("Released {} stale changelog poll lease(s)", reset);
        } catch (RuntimeException ex) {
            log.error("Stale changelog-lease sweep failed: {}", ex.toString());
        }
    }

    private void pollLinkSafely(UUID linkId) {
        try {
            CorrelationContext.withCorrelation(UUID.randomUUID(), () -> pollLink(linkId));
        } catch (RuntimeException ex) {
            // One bad link must not kill the pool thread for the next tick.
            log.error("Changelog poll failed for link {}: {}", linkId, ex.toString());
        }
    }

    // ── Per-link poll ─────────────────────────────────────────────────────────

    /** Visible for tests. Claims the lease, polls, always releases the lease. */
    void pollLink(UUID linkId) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime staleCutoff = now.minus(Duration.ofMillis(leaseTimeoutMs));
        Optional<ClaimedPoll> claim = txOps.tryClaim(linkId, now, staleCutoff);
        if (claim.isEmpty()) return;   // another tick/instance holds the lease
        try {
            doPoll(linkId, claim.get(), now);
        } catch (RuntimeException ex) {
            log.warn("Changelog poll error for link {}: {}", linkId, ex.toString());
            txOps.recordError(linkId, ex.toString(), now);
        } finally {
            txOps.release(linkId);
        }
    }

    private void doPoll(UUID linkId, ClaimedPoll cp, OffsetDateTime now) {
        ReplicationLinkSnapshot snap = readOps.snapshotById(linkId).orElse(null);
        if (snap == null) return;
        DirectoryConnection source = snap.sourceDirectory();
        ChangelogStrategy strategy = strategyFor(cp.format());

        PollPage page = readPage(source, strategy, cp, snap.sourceBaseDn());

        if (page.head() == null) {
            // Without a source head we can neither seed nor track lag. The C3R
            // test-changelog capability check hard-fails this at config time.
            txOps.recordError(linkId, "source root DSE exposes no lastChangeNumber", now);
            return;
        }
        long head = page.head();

        if (cp.cursor() == null) {
            // First run: seed from the current head without replaying history;
            // an initial reconciliation brings pre-existing entries into parity.
            txOps.seed(linkId, head, now);
            log.info("Seeded changelog cursor for link {} at changeNumber {}", linkId, head);
            return;
        }
        long cursor = cp.cursor();

        UUID correlationId = CorrelationContext.currentOrEphemeral();
        List<PendingReplicationEvent> pending = new ArrayList<>();
        long lastProcessed = cursor;

        for (SearchResultEntry entry : ascendingByChangeNumber(page.entries(), cursor)) {
            long cn = entry.getAttributeValueAsLong("changeNumber");
            try {
                Optional<ChangelogChange> extracted = strategy.extractChange(entry);
                if (extracted.isPresent() && !isPortalOwnWrite(entry, source)) {
                    ChangelogChange change = extracted.get();
                    String targetDn = DnMapper.map(change.sourceDn(), snap);
                    if (targetDn != null) {
                        Map<String, Object> payload = ReplicationPayloadMapper.buildMappedPayload(
                                change.operation(), change.rawPayload(), snap, correlationId);
                        pending.add(new PendingReplicationEvent(linkId, ReplicationEnqueueSource.SOURCE_CHANGELOG,
                                change.operation(), change.sourceDn(), targetDn, payload, cn));
                    }
                    // targetDn == null → out of scope for this link; skip + advance.
                }
                // empty extract → non-replicable entry; skip + advance.
                lastProcessed = cn;
            } catch (ChangelogParseException pe) {
                // Don't wedge the link on a poison entry; skip past it. C3R
                // upgrades this to a dead-letter. Reconciliation re-derives the DN.
                log.error("Changelog parse failed for link {} changeNumber {}: {} — skipping",
                        linkId, cn, pe.getMessage());
                lastProcessed = cn;
            }
        }

        persistDeduplicated(linkId, pending);

        if (lastProcessed > cursor) {
            // Persist-before-advance: a crash here replays a bounded, de-duplicated
            // prefix. CAS guards against a stale lease / concurrent operator edit.
            if (!txOps.advance(linkId, cursor, lastProcessed, head, now)) {
                log.warn("Changelog cursor CAS lost for link {} (expected {}); will retry next poll",
                        linkId, cursor);
            }
        } else {
            txOps.observe(linkId, head, now);
        }
    }

    /** Exactly-once: drop already-enqueued change numbers, then persist the rest. */
    private void persistDeduplicated(UUID linkId, List<PendingReplicationEvent> pending) {
        if (pending.isEmpty()) return;
        List<Long> numbers = pending.stream().map(PendingReplicationEvent::sourceChangeNumber).toList();
        Set<Long> already = new HashSet<>(eventRepo.findExistingChangelogNumbers(linkId, numbers));
        List<PendingReplicationEvent> fresh = pending.stream()
                .filter(p -> !already.contains(p.sourceChangeNumber()))
                .toList();
        if (!fresh.isEmpty()) persister.saveAll(fresh);
    }

    // ── LDAP read ───────────────────────────────────────────────────────────────

    /** What one poll read off the source: the current head and this page's entries. */
    private record PollPage(Long head, List<SearchResultEntry> entries) {}

    private PollPage readPage(DirectoryConnection source, ChangelogStrategy strategy,
                              ClaimedPoll cp, String branchFilterDn) {
        return connectionFactory.withConnectionUnreplicated(source, iface -> {
            RootDSE dse = iface.getRootDSE();
            Long head = dse == null ? null : dse.getAttributeValueAsLong("lastChangeNumber");
            if (head == null || cp.cursor() == null) {
                // No head, or first-run seed: no entry read needed.
                return new PollPage(head, List.of());
            }
            ChangelogReadContext ctx = new ChangelogReadContext(cp.baseDn(), branchFilterDn, cp.cursor());
            SearchResult result;
            try {
                result = iface.search(strategy.buildSearchRequest(ctx, maxPerPoll));
            } catch (LDAPSearchException se) {
                // SIZE_LIMIT_EXCEEDED returns the lowest N (server-side sorted);
                // take the partial page and continue from its max next poll.
                result = se.getSearchResult();
            }
            return new PollPage(head, new ArrayList<>(result.getSearchEntries()));
        });
    }

    /** Entries with a changeNumber strictly above the cursor, in ascending order. */
    private static List<SearchResultEntry> ascendingByChangeNumber(List<SearchResultEntry> entries, long cursor) {
        return entries.stream()
                .filter(e -> e.getAttributeValue("changeNumber") != null)
                .filter(e -> e.getAttributeValueAsLong("changeNumber") > cursor)
                .sorted(Comparator.comparingLong(e -> e.getAttributeValueAsLong("changeNumber")))
                .toList();
    }

    /**
     * Loop guard (§7.2): skip a changelog entry the portal itself wrote into the
     * source (its {@code creatorsName} equals the source bind DN). Prevents a
     * directory that is both a target and a CHANGELOG source from re-capturing
     * the portal's own deliveries.
     */
    private static boolean isPortalOwnWrite(SearchResultEntry entry, DirectoryConnection source) {
        String creators = entry.getAttributeValue("creatorsName");
        String bindDn = source.getBindDn();
        if (creators == null || bindDn == null || bindDn.isBlank()) return false;
        try {
            return new DN(creators).equals(new DN(bindDn));
        } catch (LDAPException e) {
            return creators.equalsIgnoreCase(bindDn);
        }
    }
}
