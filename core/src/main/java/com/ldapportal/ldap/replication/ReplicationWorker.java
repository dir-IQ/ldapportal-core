// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.ReplicationEvent;
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
 * <p>Transactional methods live on {@link ReplicationEventTxOps},
 * NOT on this class — Spring's transactional proxy only applies to
 * cross-bean calls. An earlier version had {@code @Transactional}
 * methods on this class and called them via {@code this.xxx()},
 * which silently bypassed the proxy and ran every write without a
 * transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReplicationWorker {

    private final ReplicationEventRepository eventRepo;
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
        eventRepo.findEarliestClaimableForLink(linkId, now).ifPresent(event -> {
            int claimed = txOps.tryClaim(event.getId(), now);
            if (claimed == 0) {
                log.debug("Event {} claim race lost — skipping", event.getId());
                return;
            }
            deliverAndSettle(event);
        });
    }

    private void deliverAndSettle(ReplicationEvent event) {
        ReplicationDelivery.DeliveryResult result = delivery.deliver(event);
        if (result.success()) {
            boolean settled = txOps.markDelivered(event.getId());
            if (!settled) {
                // Claim was revoked while we held it (operator retry
                // or stale-reset sweep) — drop the result rather than
                // overwrite whatever the new owner produced.
                log.warn("Replication event {} delivered but claim was revoked — " +
                        "skipping settlement", event.getId());
            }
            return;
        }
        settleFailure(event, result.errorMessage());
    }

    private void settleFailure(ReplicationEvent event, String errorMessage) {
        int newAttempts = event.getAttempts() + 1;
        ReplicationBackoffPolicy.Outcome outcome =
                ReplicationBackoffPolicy.computeOutcome(newAttempts, OffsetDateTime.now());
        boolean settled = txOps.markFailure(event.getId(),
                outcome.status(),
                newAttempts,
                outcome.nextAttemptAt(),
                errorMessage);
        if (!settled) {
            log.warn("Replication event {} failed but claim was revoked — " +
                    "skipping failure settlement", event.getId());
            return;
        }
        if (outcome.status() == ReplicationEventStatus.DEAD_LETTERED) {
            log.warn("Replication event {} dead-lettered after {} attempts: {}",
                    event.getId(), newAttempts, errorMessage);
            // No principal — the worker isn't a user-driven action.
            // recordSystemEventNoActor preserves the action + detail
            // without forcing a synthetic actor row.
            auditService.recordSystemEventNoActor(
                    AuditAction.REPLICATION_EVENT_DEAD_LETTERED,
                    Map.of(
                            "eventId",   event.getId().toString(),
                            "linkId",    event.getLink().getId().toString(),
                            "operation", event.getOperation().name(),
                            "targetDn",  event.getTargetDn(),
                            "attempts",  newAttempts,
                            "lastError", errorMessage == null ? "" : errorMessage));
        }
    }
}
