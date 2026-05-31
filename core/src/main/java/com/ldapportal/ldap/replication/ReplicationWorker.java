// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.core.observability.CorrelationContext;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled drainer for the replication queue. Polls every 10 seconds
 * for events whose retry-time has come and applies them via
 * {@link ReplicationDelivery}.
 *
 * <p>Per-link FIFO: the worker iterates the distinct link IDs with
 * claimable events and processes <em>one</em> event per link per tick
 * (the earliest by {@code enqueued_at}). A FAILED head blocks the rest
 * of its link's queue until either it succeeds on the next retry or
 * the operator intervenes — so a wedged event can't be silently
 * bypassed by later writes against the same target.
 *
 * <p>Atomic claim: a successful CAS-style {@code tryClaim} flips the
 * row to IN_FLIGHT. If a second worker instance is ever introduced
 * (P1 doesn't ship one), the loser of the race sees a 0-row return
 * and skips the event — no double-delivery. The flip commits in its
 * own short-lived transaction so the IN_FLIGHT status is visible
 * immediately, not held open across the LDAP delivery round-trip.
 *
 * <p>Crash recovery: a separate {@link #resetStaleInFlight()} pass
 * flips IN_FLIGHT events back to PENDING when they've been claimed
 * for longer than the stale threshold (10 minutes). Without this, a
 * JVM crash between claim and settle would permanently wedge the
 * event (and behind it, every later event for the same link).
 *
 * <p><b>Snapshot-based boundary.</b> This worker holds only
 * {@link ReplicationEventSnapshot} records — never JPA entities.
 * The read tx that materialises the snapshot lives in
 * {@link ReplicationReadOps}; the write txs that settle the outcome
 * live in {@link ReplicationEventTxOps}. The worker's own methods
 * are intentionally non-transactional so the LDAP delivery round-trip
 * isn't held inside any DB transaction, and so the lazy-load bug class
 * is structurally absent: there are no proxies in scope for the
 * delivery code to accidentally dereference outside a session.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReplicationWorker {

    private final ReplicationEventRepository eventRepo;
    private final ReplicationReadOps         readOps;
    private final ReplicationDelivery        delivery;
    private final ReplicationEventTxOps      txOps;
    private final AuditService               auditService;

    /** Stale threshold for IN_FLIGHT recovery. Far enough above the
     *  longest LDAP timeout (poolConnect + poolResponse, default ~40s)
     *  that a slow delivery isn't mistaken for a crash. */
    private static final Duration STALE_IN_FLIGHT_THRESHOLD = Duration.ofMinutes(10);

    /**
     * Poll interval. 10s is short enough that operator-visible
     * latency between a source write and target delivery stays in
     * the "feels real-time" range, long enough to keep idle-CPU cost
     * negligible.
     */
    @Scheduled(fixedDelayString = "${ldapportal.replication.worker.fixed-delay-ms:10000}")
    public void drainQueue() {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            List<UUID> linkIds = eventRepo.findLinkIdsWithClaimableEvents(now);
            for (UUID linkId : linkIds) {
                drainOne(linkId, now);
            }
        } catch (Exception ex) {
            // Defensive — a @Scheduled method that throws stops being
            // scheduled until the next process restart. Catch at the
            // top level so a transient DB blip can't permanently wedge
            // the worker.
            log.error("ReplicationWorker drain pass failed: {}", ex.toString());
        }
    }

    /**
     * Separate scheduled pass that recovers events stuck in IN_FLIGHT
     * past the stale threshold. Runs at a longer interval than the
     * main drainer; finding stuck events quickly isn't necessary
     * because the per-link FIFO already prevents bypass — what
     * matters is that they don't stay stuck indefinitely.
     */
    @Scheduled(fixedDelayString = "${ldapportal.replication.worker.stale-reset-ms:60000}")
    public void resetStaleInFlight() {
        try {
            OffsetDateTime threshold = OffsetDateTime.now().minus(STALE_IN_FLIGHT_THRESHOLD);
            int reset = txOps.resetStaleInFlight(threshold);
            if (reset > 0) {
                log.warn("Reset {} stuck IN_FLIGHT replication event(s) to PENDING — " +
                        "worker likely crashed mid-delivery", reset);
            }
        } catch (Exception ex) {
            log.error("ReplicationWorker stale-reset pass failed: {}", ex.toString());
        }
    }

    /**
     * Process the earliest claimable event for a single link. Returns
     * without doing work if no event is available or the claim race
     * is lost.
     */
    void drainOne(UUID linkId, OffsetDateTime now) {
        readOps.earliestClaimableSnapshot(linkId, now).ifPresent(event -> {
            int claimed = txOps.tryClaim(event.id(), now);
            if (claimed == 0) {
                log.debug("Event {} claim race lost — skipping", event.id());
                return;
            }
            deliverAndSettle(event);
        });
    }

    private void deliverAndSettle(ReplicationEventSnapshot event) {
        // Each delivery runs in its own correlation scope (a fresh id), so
        // delivery-side audit rows pivot independently of the source-side
        // ones. The originating source id is preserved separately on the
        // event payload (stamped at enqueue) and surfaced in the dead-letter
        // audit detail as sourceCorrelationId. withCorrelation restores the
        // previous (here: empty) scope in its finally, so the @Scheduled
        // worker thread doesn't leak the id into the next event.
        CorrelationContext.withCorrelation(UUID.randomUUID(), () -> {
            ReplicationDelivery.DeliveryResult result = delivery.deliver(event);
            if (result.success()) {
                boolean settled = txOps.markDelivered(event.id());
                if (!settled) {
                    // Claim was revoked while we held it (operator retry
                    // or stale-reset sweep) — drop the result rather than
                    // overwrite whatever the new owner produced.
                    log.warn("Replication event {} delivered but claim was revoked — " +
                            "skipping settlement", event.id());
                }
                return;
            }
            settleFailure(event, result.errorMessage());
        });
    }

    private void settleFailure(ReplicationEventSnapshot event, String errorMessage) {
        int newAttempts = event.attempts() + 1;
        ReplicationBackoffPolicy.Outcome outcome =
                ReplicationBackoffPolicy.computeOutcome(newAttempts, OffsetDateTime.now());
        boolean settled = txOps.markFailure(event.id(),
                outcome.status(),
                newAttempts,
                outcome.nextAttemptAt(),
                errorMessage);
        if (!settled) {
            log.warn("Replication event {} failed but claim was revoked — " +
                    "skipping failure settlement", event.id());
            return;
        }
        if (outcome.status() == ReplicationEventStatus.DEAD_LETTERED) {
            log.warn("Replication event {} dead-lettered after {} attempts: {}",
                    event.id(), newAttempts, errorMessage);
            // No principal — the worker isn't a user-driven action.
            // recordSystemEventNoActor preserves the action + detail
            // without forcing a synthetic actor row. The delivery-side id
            // (the active CorrelationContext scope from deliverAndSettle)
            // lands on the audit row's correlation_id column; the originating
            // source-side id travels in detail.sourceCorrelationId so an
            // operator can pivot a dead-lettered event back to the audit
            // rows of the operation that produced it. Mutable map because
            // sourceCorrelationId may be absent (pre-R2 events) and Map.of
            // rejects null values.
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("eventId",   event.id().toString());
            detail.put("linkId",    event.link().id().toString());
            detail.put("operation", event.operation().name());
            detail.put("targetDn",  event.targetDn());
            detail.put("attempts",  newAttempts);
            detail.put("lastError", errorMessage == null ? "" : errorMessage);
            // The originating source-side trace id (stamped on the payload at
            // enqueue) travels into the dead-letter audit detail so the row
            // pivots back to the originating operation.
            ReplicationPayloadCodec.correlationId(event.payload())
                    .ifPresent(src -> detail.put("sourceCorrelationId", src));
            auditService.recordSystemEventNoActor(
                    AuditAction.REPLICATION_EVENT_DEAD_LETTERED, detail);
        }
    }
}
