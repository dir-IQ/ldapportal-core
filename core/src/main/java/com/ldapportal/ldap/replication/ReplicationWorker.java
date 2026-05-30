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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 * and skips the event — no double-delivery.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReplicationWorker {

    private final ReplicationEventRepository eventRepo;
    private final ReplicationDelivery        delivery;
    private final AuditService               auditService;

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
     * Process the earliest claimable event for a single link. Returns
     * without doing work if no event is available, the claim race is
     * lost, or the link's head is in-flight elsewhere.
     */
    void drainOne(UUID linkId, OffsetDateTime now) {
        eventRepo.findEarliestClaimableForLink(linkId, now).ifPresent(event -> {
            int claimed = claim(event.getId());
            if (claimed == 0) {
                log.debug("Event {} claim race lost — skipping", event.getId());
                return;
            }
            deliverAndSettle(event);
        });
    }

    /**
     * The atomic claim runs in its own tiny transaction so the
     * IN_FLIGHT flip commits immediately — other workers (or this
     * worker's next tick) see the up-to-date status without waiting
     * for delivery to complete. REQUIRES_NEW even though the outer
     * caller has no transaction; harmless either way.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int claim(UUID eventId) {
        return eventRepo.tryClaim(eventId);
    }

    private void deliverAndSettle(ReplicationEvent event) {
        ReplicationDelivery.DeliveryResult result = delivery.deliver(event);
        if (result.success()) {
            settleSuccess(event.getId());
            return;
        }
        settleFailure(event, result.errorMessage());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleSuccess(UUID eventId) {
        eventRepo.markDelivered(eventId, OffsetDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleFailure(ReplicationEvent event, String errorMessage) {
        int newAttempts = event.getAttempts() + 1;
        ReplicationBackoffPolicy.Outcome outcome =
                ReplicationBackoffPolicy.computeOutcome(newAttempts, OffsetDateTime.now());
        eventRepo.markFailure(event.getId(),
                outcome.status(),
                newAttempts,
                outcome.nextAttemptAt(),
                errorMessage);
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
