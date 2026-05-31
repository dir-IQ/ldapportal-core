// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.service.AuditService;
import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplicationWorkerTest {

    @Mock private ReplicationEventRepository eventRepo;
    @Mock private ReplicationReadOps         readOps;
    @Mock private ReplicationDelivery        delivery;
    @Mock private ReplicationEventTxOps      txOps;
    @Mock private AuditService               auditService;
    @InjectMocks private ReplicationWorker   worker;

    @Test
    void emptyQueue_noOp() {
        when(eventRepo.findLinkIdsWithClaimableEvents(any())).thenReturn(List.of());

        worker.drainQueue();

        verify(readOps, never()).earliestClaimableSnapshot(any(), any());
        verify(delivery, never()).deliver(any());
    }

    @Test
    void claimRaceLost_skipsEventWithoutDelivering() {
        // Another worker (or the next scheduled tick) claimed the row
        // between our findLinkIds and tryClaim — the second worker
        // sees 0 rows affected on tryClaim and skips delivery. This is
        // the contract that lets a future multi-worker deployment ship
        // without double-delivery.
        UUID linkId = UUID.randomUUID();
        ReplicationEventSnapshot event = event(linkId);
        when(eventRepo.findLinkIdsWithClaimableEvents(any())).thenReturn(List.of(linkId));
        when(readOps.earliestClaimableSnapshot(eq(linkId), any())).thenReturn(Optional.of(event));
        when(txOps.tryClaim(eq(event.id()), any())).thenReturn(0);  // race lost

        worker.drainQueue();

        verify(delivery, never()).deliver(any());
    }

    @Test
    void successfulDelivery_marksDelivered() {
        UUID linkId = UUID.randomUUID();
        ReplicationEventSnapshot event = event(linkId);
        stubClaim(linkId, event, 1);
        when(delivery.deliver(event)).thenReturn(
                new ReplicationDelivery.DeliveryResult(true, ResultCode.SUCCESS, null));

        worker.drainQueue();

        verify(txOps).markDelivered(eq(event.id()));
        verify(txOps, never()).markFailure(any(), any(), anyInt(), any(), any());
    }

    @Test
    void failedDelivery_schedulesRetryViaBackoffPolicy() {
        // First failure → status=FAILED, retry scheduled per backoff
        // policy (30s for attempts=1). Worker passes the new attempts
        // count and the error message to markFailure.
        UUID linkId = UUID.randomUUID();
        ReplicationEventSnapshot event = event(linkId, 0);
        stubClaim(linkId, event, 1);
        when(delivery.deliver(event)).thenReturn(
                new ReplicationDelivery.DeliveryResult(false, ResultCode.UNAVAILABLE,
                        "target unreachable"));

        worker.drainQueue();

        ArgumentCaptor<ReplicationEventStatus> statusCap = ArgumentCaptor.forClass(ReplicationEventStatus.class);
        ArgumentCaptor<Integer> attemptsCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> errorCap = ArgumentCaptor.forClass(String.class);
        verify(txOps).markFailure(eq(event.id()),
                statusCap.capture(), attemptsCap.capture(), any(OffsetDateTime.class), errorCap.capture());
        assertThat(statusCap.getValue()).isEqualTo(ReplicationEventStatus.FAILED);
        assertThat(attemptsCap.getValue()).isEqualTo(1);
        assertThat(errorCap.getValue()).isEqualTo("target unreachable");
    }

    @Test
    void retryBudgetExhausted_marksDeadLettered_andEmitsAudit() {
        // Sixth failure → DEAD_LETTERED. Two contracts pinned here:
        // (1) the status transition itself, (2) the actor-less audit
        // emission for the DEAD_LETTERED transition — without it, the
        // operator-facing audit log misses the moment the queue gave
        // up. Audit goes through recordSystemEventNoActor because
        // the worker has no principal.
        UUID linkId = UUID.randomUUID();
        ReplicationEventSnapshot event = event(linkId, ReplicationBackoffPolicy.MAX_ATTEMPTS,
                ReplicationOperationType.MODIFY, "uid=alice,dc=target,dc=com");
        stubClaim(linkId, event, 1);
        when(delivery.deliver(event)).thenReturn(
                new ReplicationDelivery.DeliveryResult(false, null, "still failing"));
        // markFailure settled successfully (claim wasn't revoked) so
        // the DEAD_LETTERED audit must fire.
        when(txOps.markFailure(any(), any(), anyInt(), any(), any())).thenReturn(true);

        worker.drainQueue();

        ArgumentCaptor<ReplicationEventStatus> statusCap = ArgumentCaptor.forClass(ReplicationEventStatus.class);
        verify(txOps).markFailure(eq(event.id()),
                statusCap.capture(), eq(ReplicationBackoffPolicy.MAX_ATTEMPTS + 1),
                any(), any());
        assertThat(statusCap.getValue()).isEqualTo(ReplicationEventStatus.DEAD_LETTERED);
        verify(auditService).recordSystemEventNoActor(
                eq(AuditAction.REPLICATION_EVENT_DEAD_LETTERED), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadLetterAudit_carriesSourceCorrelationId_fromEventPayload() {
        // R2 pivot: the dead-letter audit detail must surface the source-side
        // correlation id the enqueuer stamped on the event payload, so an
        // operator can jump from a dead-lettered event to the audit rows of
        // the originating operation.
        UUID linkId = UUID.randomUUID();
        String sourceCorr = UUID.randomUUID().toString();
        ReplicationLinkSnapshot link = new ReplicationLinkSnapshot(
                linkId, "test-link", null, null, null, null, true, false, List.of());
        ReplicationEventSnapshot event = new ReplicationEventSnapshot(
                UUID.randomUUID(), link,
                ReplicationEnqueueSource.APP_INTERCEPT, ReplicationOperationType.MODIFY,
                "uid=alice,dc=src", "uid=alice,dc=corp",
                Map.of("correlationId", sourceCorr),
                ReplicationBackoffPolicy.MAX_ATTEMPTS, OffsetDateTime.now());
        stubClaim(linkId, event, 1);
        when(delivery.deliver(event)).thenReturn(
                new ReplicationDelivery.DeliveryResult(false, null, "still failing"));
        when(txOps.markFailure(any(), any(), anyInt(), any(), any())).thenReturn(true);

        worker.drainQueue();

        ArgumentCaptor<Map<String, Object>> detailCap = ArgumentCaptor.forClass(Map.class);
        verify(auditService).recordSystemEventNoActor(
                eq(AuditAction.REPLICATION_EVENT_DEAD_LETTERED), detailCap.capture());
        assertThat(detailCap.getValue()).containsEntry("sourceCorrelationId", sourceCorr);
    }

    @Test
    void deadLetterAuditSuppressed_whenClaimRevokedMidFlight() {
        // Defensive: if the IN_FLIGHT claim was revoked while we held
        // it (operator clicked Retry on the dashboard, or the stale-
        // reset sweep ran in a separate process), markFailure returns
        // false because the WHERE-status guard filtered the update.
        // The worker MUST suppress the DEAD_LETTERED audit in that
        // case — the row is no longer ours to dead-letter, and another
        // owner's verdict is what counts.
        UUID linkId = UUID.randomUUID();
        ReplicationEventSnapshot event = event(linkId, ReplicationBackoffPolicy.MAX_ATTEMPTS,
                ReplicationOperationType.MODIFY, "uid=alice,dc=corp");
        stubClaim(linkId, event, 1);
        when(delivery.deliver(event)).thenReturn(
                new ReplicationDelivery.DeliveryResult(false, null, "still failing"));
        when(txOps.markFailure(any(), any(), anyInt(), any(), any())).thenReturn(false);

        worker.drainQueue();

        verify(auditService, never()).recordSystemEventNoActor(any(), any());
    }

    @Test
    void retryUnderBudget_doesNotEmitDeadLetterAudit() {
        // First failure → FAILED, not DEAD_LETTERED. The audit-on-
        // dead-letter event must NOT fire here, or we'd see one audit
        // per attempt instead of one when the budget runs out.
        UUID linkId = UUID.randomUUID();
        ReplicationEventSnapshot event = event(linkId, 0);
        stubClaim(linkId, event, 1);
        when(delivery.deliver(event)).thenReturn(
                new ReplicationDelivery.DeliveryResult(false, null, "transient"));

        worker.drainQueue();

        verify(auditService, never()).recordSystemEventNoActor(any(), any());
    }

    @Test
    void perLinkFifo_processesOneEventPerLinkPerTick() {
        // Two links, each with claimable events. The worker processes
        // exactly one (the earliest) per link per tick — pin the
        // single-event-per-link contract.
        UUID linkA = UUID.randomUUID();
        UUID linkB = UUID.randomUUID();
        ReplicationEventSnapshot eventA = event(linkA);
        ReplicationEventSnapshot eventB = event(linkB);
        when(eventRepo.findLinkIdsWithClaimableEvents(any())).thenReturn(List.of(linkA, linkB));
        when(readOps.earliestClaimableSnapshot(eq(linkA), any())).thenReturn(Optional.of(eventA));
        when(readOps.earliestClaimableSnapshot(eq(linkB), any())).thenReturn(Optional.of(eventB));
        when(txOps.tryClaim(any(), any())).thenReturn(1);
        when(delivery.deliver(any())).thenReturn(
                new ReplicationDelivery.DeliveryResult(true, ResultCode.SUCCESS, null));

        worker.drainQueue();

        // Both links' heads delivered; markDelivered called once per link.
        verify(txOps).markDelivered(eq(eventA.id()));
        verify(txOps).markDelivered(eq(eventB.id()));
    }

    @Test
    void resetStaleInFlight_delegatesToTxOps() {
        // Pins that the stale-recovery path delegates to the
        // transactional sibling bean (so REQUIRES_NEW commits per
        // call), not to eventRepo directly. Without this, a worker
        // crash mid-delivery would leave events stuck IN_FLIGHT
        // forever — the per-link FIFO then blocks every later event
        // for the same link.
        when(txOps.resetStaleInFlight(any())).thenReturn(2);

        worker.resetStaleInFlight();

        verify(txOps).resetStaleInFlight(any(OffsetDateTime.class));
    }

    @Test
    void resetStaleInFlight_absorbsExceptions() {
        // Same scheduled-method-must-not-throw contract as drainQueue.
        when(txOps.resetStaleInFlight(any()))
                .thenThrow(new RuntimeException("DB blip"));
        worker.resetStaleInFlight();  // must not throw
    }

    @Test
    void unexpectedExceptionInPass_doesNotKillScheduler() {
        // A thrown exception inside drainQueue would stop the
        // @Scheduled method from being scheduled again until process
        // restart. The outer try/catch must absorb it.
        when(eventRepo.findLinkIdsWithClaimableEvents(any()))
                .thenThrow(new RuntimeException("DB blip"));

        // Must not throw.
        worker.drainQueue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubClaim(UUID linkId, ReplicationEventSnapshot event, int claimResult) {
        when(eventRepo.findLinkIdsWithClaimableEvents(any())).thenReturn(List.of(linkId));
        when(readOps.earliestClaimableSnapshot(eq(linkId), any())).thenReturn(Optional.of(event));
        when(txOps.tryClaim(eq(event.id()), any())).thenReturn(claimResult);
    }

    private ReplicationEventSnapshot event(UUID linkId) {
        return event(linkId, 0, ReplicationOperationType.DELETE, "uid=alice,dc=corp");
    }

    private ReplicationEventSnapshot event(UUID linkId, int attempts) {
        return event(linkId, attempts, ReplicationOperationType.DELETE, "uid=alice,dc=corp");
    }

    private ReplicationEventSnapshot event(UUID linkId, int attempts,
                                            ReplicationOperationType op, String targetDn) {
        ReplicationLinkSnapshot link = new ReplicationLinkSnapshot(
                linkId, "test-link", null, null, null, null, true, false, List.of());
        return new ReplicationEventSnapshot(
                UUID.randomUUID(), link,
                ReplicationEnqueueSource.APP_INTERCEPT, op,
                "uid=alice,dc=src", targetDn,
                Map.of(), attempts, OffsetDateTime.now());
    }
}
