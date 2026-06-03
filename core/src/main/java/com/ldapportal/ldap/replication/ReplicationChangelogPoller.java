// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.observability.CorrelationContext;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.ChangelogHealth;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;
import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.changelog.AccesslogStrategy;
import com.ldapportal.ldap.changelog.ChangelogChange;
import com.ldapportal.ldap.changelog.ChangelogParseException;
import com.ldapportal.ldap.changelog.ChangelogReadContext;
import com.ldapportal.ldap.changelog.ChangelogStrategy;
import com.ldapportal.ldap.changelog.DirSyncChangelogStrategy;
import com.ldapportal.ldap.changelog.DseeChangelogStrategy;
import com.ldapportal.ldap.replication.ChangelogPollTxOps.ClaimedPoll;
import com.ldapportal.ldap.replication.reconcile.ReconciliationService;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import com.ldapportal.service.AuditService;
import com.unboundid.ldap.sdk.Attribute;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <p><b>Reliability (§7A):</b> each poll reads the source's {@code first/last
 * ChangeNumber} from the root DSE and guards two danger conditions before
 * draining — a <b>cursor reset</b> ({@code lastChangeNumber < cursor}; halt +
 * flag {@code CURSOR_RESET}) and a <b>gap</b> ({@code cursor+1 <
 * firstChangeNumber}; fast-forward + flag {@code GAP_DETECTED} + trigger
 * reconciliation). A poison entry is <b>dead-lettered</b> with its raw content,
 * not silently skipped. Per-link {@code changelogHealth} + lag is refreshed
 * every poll. The exclude filter is the C3X layer.
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
    private final ReconciliationService      reconciliationService;
    private final AuditService               auditService;
    /** May be null in direct-construction unit tests → entitlement gate treated as open. */
    private final EntitlementService         entitlementService;

    @Value("${ldapportal.replication.changelog.pool-size:2}")
    private int poolSize;
    @Value("${ldapportal.replication.changelog.max-per-poll:500}")
    private int maxPerPoll;
    /** A poll lease older than this is presumed orphaned by a crash. */
    @Value("${ldapportal.replication.changelog.lease-timeout-ms:300000}")
    private long leaseTimeoutMs;
    /** Lag (source head − cursor) above this flags the link LAGGING. */
    @Value("${ldapportal.replication.changelog.lag-threshold:1000}")
    private long lagThreshold;

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
        // A cursor-reset link is halted until an operator reseeds (which clears
        // the health and the cursor — e.g. the capture-mode toggle). Skip it
        // entirely: re-detecting + re-auditing every poll would storm the audit
        // log / SIEM, and re-reading the changelog is wasted work.
        if (cp.health() == ChangelogHealth.CURSOR_RESET) return;

        ReplicationLinkSnapshot snap = readOps.snapshotById(linkId).orElse(null);
        if (snap == null) return;
        DirectoryConnection source = snap.sourceDirectory();
        ChangelogStrategy strategy = strategyFor(cp.format());

        PollPage page = readPage(source, strategy, cp, snap.sourceBaseDn());

        if (page.head() == null) {
            // Without a source head we can neither seed, track lag, nor detect
            // gap/reset. The C3R test-changelog check hard-fails this at config time.
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

        // §7A.2 cursor reset: the source head is below our cursor — the changelog
        // was reinitialized (restore / rebuild). Halt with zero advancement (the
        // nastiest failure mode: replication stalls with no errors) until an
        // operator reseeds (or toggles capture mode, which re-seeds).
        if (head < cursor) {
            txOps.markCursorReset(linkId, head, now);
            audit(AuditAction.REPLICATION_CHANGELOG_CURSOR_RESET,
                    detail(linkId, "cursor", cursor, "sourceHead", head));
            log.error("Changelog cursor reset for link {}: source head {} < cursor {} — "
                    + "halting until reseed", linkId, head, cursor);
            return;
        }

        // §7A.1 gap: entries were trimmed before we read them. Fast-forward past
        // the lost span and let reconciliation repair it; never a silent skip.
        Long first = page.firstChangeNumber();
        if (first != null && cursor + 1 < first) {
            long fastForward = first - 1;
            if (txOps.markGap(linkId, cursor, fastForward, head, now)) {
                audit(AuditAction.REPLICATION_CHANGELOG_GAP_DETECTED,
                        detail(linkId, "cursor", cursor, "firstChangeNumber", first,
                                "fastForwardedTo", fastForward));
                log.error("Changelog gap for link {}: cursor {} below firstChangeNumber {} — "
                        + "fast-forwarding to {} and triggering reconciliation",
                        linkId, cursor, first, fastForward);
                triggerReconcile(linkId);
            }
            return;
        }

        UUID correlationId = CorrelationContext.currentOrEphemeral();
        List<PendingReplicationEvent> pending = new ArrayList<>();
        List<PoisonEntry> poison = new ArrayList<>();
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
                // §7A.3 poison policy: dead-letter the raw entry (recoverable +
                // audited) rather than silently skip it or wedge the link. Only
                // if it's in scope; an out-of-scope poison DN isn't ours to keep.
                String poisonSourceDn = entry.getAttributeValue("targetDN");
                String poisonTargetDn = poisonSourceDn == null ? null : DnMapper.map(poisonSourceDn, snap);
                if (poisonTargetDn != null) {
                    poison.add(new PoisonEntry(cn, operationFor(entry), poisonSourceDn, poisonTargetDn,
                            rawEntryPayload(entry, pe.getMessage()), pe.getMessage()));
                }
                lastProcessed = cn;
            }
        }

        persistDeduplicated(linkId, pending, poison);

        ChangelogHealth health = healthFor(head, lastProcessed);
        if (lastProcessed > cursor) {
            // Persist-before-advance: a crash here replays a bounded, de-duplicated
            // prefix. CAS guards against a stale lease / concurrent operator edit.
            if (!txOps.advance(linkId, cursor, lastProcessed, head, health, now)) {
                log.warn("Changelog cursor CAS lost for link {} (expected {}); will retry next poll",
                        linkId, cursor);
            }
        } else {
            txOps.observe(linkId, head, health, now);
        }
    }

    /**
     * Exactly-once: drop already-enqueued change numbers (crash replay /
     * concurrent re-read), then persist fresh PENDING events and dead-letter
     * fresh poison entries.
     */
    private void persistDeduplicated(UUID linkId, List<PendingReplicationEvent> pending,
                                     List<PoisonEntry> poison) {
        if (pending.isEmpty() && poison.isEmpty()) return;
        List<Long> numbers = new ArrayList<>(pending.size() + poison.size());
        pending.forEach(p -> numbers.add(p.sourceChangeNumber()));
        poison.forEach(p -> numbers.add(p.cn()));
        Set<Long> already = new HashSet<>(eventRepo.findExistingChangelogNumbers(linkId, numbers));

        List<PendingReplicationEvent> fresh = pending.stream()
                .filter(p -> !already.contains(p.sourceChangeNumber()))
                .toList();
        if (!fresh.isEmpty()) persister.saveAll(fresh);

        for (PoisonEntry p : poison) {
            if (already.contains(p.cn())) continue;
            persister.saveDeadLetteredChangelogEvent(
                    linkId, p.operation(), p.sourceDn(), p.targetDn(), p.payload(), p.cn(), p.error());
            audit(AuditAction.REPLICATION_CHANGELOG_ENTRY_DEAD_LETTERED,
                    detail(linkId, "sourceChangeNumber", p.cn(), "sourceDn", p.sourceDn(), "error", p.error()));
            log.error("Dead-lettered poison changelog entry for link {} changeNumber {}: {}",
                    linkId, p.cn(), p.error());
        }
    }

    private ChangelogHealth healthFor(long head, long cursor) {
        return Math.max(0L, head - cursor) > lagThreshold ? ChangelogHealth.LAGGING : ChangelogHealth.HEALTHY;
    }

    private void triggerReconcile(UUID linkId) {
        try {
            // MANUAL fires independent of reconcileEnabled — a changelog link can
            // use capture without periodic reconciliation yet still get repaired.
            reconciliationService.trigger(linkId, ReconciliationRunTrigger.MANUAL, null);
        } catch (RuntimeException ex) {
            log.error("Gap-recovery reconciliation trigger failed for link {}: {}", linkId, ex.toString());
        }
    }

    private void audit(AuditAction action, Map<String, Object> detail) {
        auditService.recordSystemEventNoActor(action, detail);
    }

    /** A poison entry awaiting dead-letter (in DN scope), captured during the drain. */
    private record PoisonEntry(long cn, ReplicationOperationType operation, String sourceDn,
                               String targetDn, Map<String, Object> payload, String error) {}

    // ── LDAP read ───────────────────────────────────────────────────────────────

    /** What one poll read off the source: the head, the oldest surviving entry, and the page. */
    private record PollPage(Long head, Long firstChangeNumber, List<SearchResultEntry> entries) {}

    private PollPage readPage(DirectoryConnection source, ChangelogStrategy strategy,
                              ClaimedPoll cp, String branchFilterDn) {
        return connectionFactory.withConnectionUnreplicated(source, iface -> {
            RootDSE dse = iface.getRootDSE();
            Long head = dse == null ? null : dse.getAttributeValueAsLong("lastChangeNumber");
            Long first = dse == null ? null : dse.getAttributeValueAsLong("firstChangeNumber");
            if (head == null || cp.cursor() == null) {
                // No head, or first-run seed: no entry read needed.
                return new PollPage(head, first, List.of());
            }
            ChangelogReadContext ctx = new ChangelogReadContext(cp.baseDn(), branchFilterDn, cp.cursor());
            SearchResult result;
            try {
                result = iface.search(strategy.buildSearchRequest(ctx, maxPerPoll));
            } catch (LDAPSearchException se) {
                // SIZE_LIMIT_EXCEEDED returns the lowest N (server-side sorted /
                // natural append order); take the partial page and continue from
                // its max next poll. Warn so a sustained backlog is visible — the
                // multi-page catch-up budget is a C3R refinement.
                log.warn("Changelog page truncated for source [{}] ({} entries, more pending) — "
                                + "draining incrementally", source.getDisplayName(), maxPerPoll);
                result = se.getSearchResult();
            }
            return new PollPage(head, first, new ArrayList<>(result.getSearchEntries()));
        });
    }

    /** Entries with a (parseable) changeNumber strictly above the cursor, ascending. */
    private static List<SearchResultEntry> ascendingByChangeNumber(List<SearchResultEntry> entries, long cursor) {
        // Filter on getAttributeValueAsLong (not getAttributeValue): a present
        // but non-numeric changeNumber returns null here, and unboxing it in the
        // > comparison or the comparator would NPE and wedge the link. A
        // malformed entry is skipped (reconciliation re-derives it) rather than
        // stalling the whole link.
        return entries.stream()
                .filter(e -> e.getAttributeValueAsLong("changeNumber") != null)
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

    /** Best-effort operation for a poison entry's forensic dead-letter row (changeType is readable). */
    private static ReplicationOperationType operationFor(SearchResultEntry entry) {
        String changeType = entry.getAttributeValue("changeType");
        if (changeType == null) return ReplicationOperationType.MODIFY;
        return switch (changeType.toLowerCase(Locale.ROOT)) {
            case "add" -> ReplicationOperationType.ADD;
            case "delete" -> ReplicationOperationType.DELETE;
            case "modrdn", "moddn" -> ReplicationOperationType.MODIFY_DN;
            default -> ReplicationOperationType.MODIFY;
        };
    }

    /** Raw changelog entry + parse error, for the dead-letter row's forensic payload. */
    private static Map<String, Object> rawEntryPayload(SearchResultEntry entry, String error) {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        for (Attribute a : entry.getAttributes()) {
            attrs.put(a.getName(), Arrays.asList(a.getValues()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rawAttributes", attrs);
        payload.put("parseError", error == null ? "" : error);
        return payload;
    }

    /** Build an audit detail map, tolerating null values (Map.of does not). */
    private static Map<String, Object> detail(UUID linkId, Object... kv) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("linkId", linkId.toString());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            detail.put(String.valueOf(kv[i]), kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return detail;
    }
}
