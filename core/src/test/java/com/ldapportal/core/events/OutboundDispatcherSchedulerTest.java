// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import com.ldapportal.core.events.channel.DeliveryOutcome;
import com.ldapportal.core.events.channel.OutboundChannel;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
import com.ldapportal.core.events.snapshot.EventSubscriptionSnapshot;
import com.ldapportal.core.events.snapshot.OutboxEntrySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboundDispatcherSchedulerTest {

    @Mock private OutboxEntryRepository outboxRepository;
    @Mock private OutboundEventReadOps readOps;
    @Mock private OutboundChannel channel;
    @Mock private TransactionTemplate txTemplate;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-04-23T10:00:00Z"), ZoneOffset.UTC);

    private OutboundDispatcherScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(channel.type()).thenReturn(ChannelType.WEBHOOK);
        scheduler = new OutboundDispatcherScheduler(
                outboxRepository, readOps,
                List.of(channel), txTemplate, fixedClock);

        // txTemplate.execute just invokes the callback with a fake status.
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        // executeWithoutResult too (it delegates to execute under the hood).
        doAnswer(inv -> {
            Runnable r = null;
            // TransactionTemplate.executeWithoutResult takes a Consumer<TransactionStatus>;
            // fake it simply.
            org.springframework.transaction.support.TransactionCallbackWithoutResult body =
                new org.springframework.transaction.support.TransactionCallbackWithoutResult() {
                    @Override protected void doInTransactionWithoutResult(
                            org.springframework.transaction.TransactionStatus s) {
                        java.util.function.Consumer<org.springframework.transaction.TransactionStatus> c =
                            inv.getArgument(0);
                        c.accept(s);
                    }
                };
            body.doInTransaction(null);
            return null;
        }).when(txTemplate).executeWithoutResult(any());
    }

    @Test
    void dispatch_claimsAndDelegatesOnSuccess() {
        UUID id = UUID.randomUUID();
        stageDispatch(id, 1, true);
        when(channel.deliver(any(), any())).thenReturn(DeliveryOutcome.success(200));

        scheduler.dispatch();

        ArgumentCaptor<OutboxEntry> saveCaptor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository, atLeastOnce()).save(saveCaptor.capture());
        OutboxEntry saved = saveCaptor.getAllValues().get(saveCaptor.getAllValues().size() - 1);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.DELIVERED);
        assertThat(saved.getDeliveredAt()).isEqualTo(fixedClock.instant());
    }

    @Test
    void dispatch_transientFailure_reschedulesWithBackoff() {
        UUID id = UUID.randomUUID();
        stageDispatch(id, 1, true);    // first attempt just happened (claim incremented it)
        when(channel.deliver(any(), any()))
                .thenReturn(DeliveryOutcome.transientFailure("503", 503));

        scheduler.dispatch();

        ArgumentCaptor<OutboxEntry> cap = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository, atLeastOnce()).save(cap.capture());
        OutboxEntry saved = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        // 1-minute base backoff + up-to-20% jitter
        Duration sinceNow = Duration.between(fixedClock.instant(), saved.getNextAttemptAt());
        assertThat(sinceNow).isGreaterThanOrEqualTo(Duration.ofMinutes(1));
        assertThat(sinceNow).isLessThanOrEqualTo(Duration.ofMinutes(1).plusSeconds(12));
        assertThat(saved.getLastHttpStatus()).isEqualTo(503);
        assertThat(saved.getLastError()).contains("503");
    }

    @Test
    void dispatch_permanentFailure_deadLetters() {
        UUID id = UUID.randomUUID();
        stageDispatch(id, 1, true);
        when(channel.deliver(any(), any()))
                .thenReturn(DeliveryOutcome.permanentFailure("401", 401));

        scheduler.dispatch();

        ArgumentCaptor<OutboxEntry> cap = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository, atLeastOnce()).save(cap.capture());
        OutboxEntry saved = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
        assertThat(saved.getDeadLetteredAt()).isEqualTo(fixedClock.instant());
    }

    @Test
    void dispatch_transientFailureAtMaxAttempts_deadLetters() {
        UUID id = UUID.randomUUID();
        stageDispatch(id, 6, true);    // BACKOFF.length + 1; this is the final attempt
        when(channel.deliver(any(), any()))
                .thenReturn(DeliveryOutcome.transientFailure("503", 503));

        scheduler.dispatch();

        ArgumentCaptor<OutboxEntry> cap = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository, atLeastOnce()).save(cap.capture());
        OutboxEntry saved = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
    }

    @Test
    void dispatch_channelThrows_treatedAsTransient() {
        UUID id = UUID.randomUUID();
        stageDispatch(id, 1, true);
        when(channel.deliver(any(), any())).thenThrow(new RuntimeException("boom"));

        scheduler.dispatch();

        ArgumentCaptor<OutboxEntry> cap = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository, atLeastOnce()).save(cap.capture());
        OutboxEntry saved = cap.getAllValues().get(cap.getAllValues().size() - 1);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getLastError()).contains("uncaught");
    }

    @Test
    void dispatch_disabledSubscription_deadLetters() {
        UUID id = UUID.randomUUID();
        stageDispatch(id, 1, /* subEnabled */ false);

        scheduler.dispatch();

        verify(channel, never()).deliver(any(), any());
        ArgumentCaptor<OutboxEntry> cap = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
    }

    @Test
    void dispatch_deletedSubscription_deadLetters() {
        UUID id = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        when(outboxRepository.claimBatch(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of(id));
        when(readOps.outboxSnapshot(id))
                .thenReturn(Optional.of(rowSnapshot(id, subId, 1)));
        when(readOps.subscriptionSnapshot(subId)).thenReturn(Optional.empty());
        when(outboxRepository.findById(id)).thenReturn(Optional.of(rowEntity(id, subId, 1)));

        scheduler.dispatch();

        verify(channel, never()).deliver(any(), any());
        ArgumentCaptor<OutboxEntry> cap = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
    }

    /**
     * Race-safety pin: if the stale-reset sweep (or a future HA-mode
     * peer dispatcher) revokes the claim while the HTTP POST is in
     * flight, the dispatcher's settle MUST NOT overwrite the new
     * owner's state. Before the {@code claimed_at} fix + status guard
     * combo, a backlog-drain claim could be false-reset within the
     * 60s sweep tick, the dispatcher's POST would complete, and
     * {@code resolveRow} would silently flip the row back to
     * DELIVERED — masking the double-delivery and overwriting
     * whatever state the new owner produced.
     */
    @Test
    void settle_dropsOutcomeWhenClaimWasRevokedMidFlight() {
        UUID id = UUID.randomUUID();
        UUID subId = UUID.randomUUID();
        when(outboxRepository.claimBatch(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of(id));
        when(readOps.outboxSnapshot(id))
                .thenReturn(Optional.of(rowSnapshot(id, subId, 1)));
        when(readOps.subscriptionSnapshot(subId))
                .thenReturn(Optional.of(subscriptionSnapshot(subId, true)));
        when(channel.deliver(any(), any())).thenReturn(DeliveryOutcome.success(200));

        // Simulate the stale-reset sweep having fired between claim
        // and settle: the row the settle loads is back to PENDING.
        OutboxEntry revoked = rowEntity(id, subId, 1);
        revoked.setStatus(OutboxStatus.PENDING);
        when(outboxRepository.findById(id)).thenReturn(Optional.of(revoked));

        scheduler.dispatch();

        // No save — settle must no-op when claim was revoked.
        verify(outboxRepository, never()).save(any());
    }

    /**
     * Structural-safety pin: the channel receives an
     * {@link EventSubscriptionSnapshot}, not the
     * {@code EventSubscription} JPA entity. The snapshot carries
     * {@code createdById} as a UUID — not the LAZY {@code createdBy}
     * Account reference — so a future channel implementation that
     * tries to enrich the webhook payload with the creator's username
     * can't accidentally trip {@code LazyInitializationException}
     * outside the read tx and silently retry-loop the row forever.
     *
     * <p>Verifying via {@code ArgumentCaptor.forClass(EventSubscriptionSnapshot.class)}
     * — if the SPI ever regresses back to the entity type, this fails
     * at compile time, not at runtime in production.
     */
    @Test
    void channelReceivesSnapshot_notEntity() {
        UUID id = UUID.randomUUID();
        stageDispatch(id, 1, true);
        when(channel.deliver(any(), any())).thenReturn(DeliveryOutcome.success(200));

        scheduler.dispatch();

        ArgumentCaptor<EventSubscriptionSnapshot> subCap =
                ArgumentCaptor.forClass(EventSubscriptionSnapshot.class);
        ArgumentCaptor<OutboxEntrySnapshot> rowCap =
                ArgumentCaptor.forClass(OutboxEntrySnapshot.class);
        verify(channel).deliver(rowCap.capture(), subCap.capture());
        assertThat(subCap.getValue()).isInstanceOf(EventSubscriptionSnapshot.class);
        assertThat(rowCap.getValue()).isInstanceOf(OutboxEntrySnapshot.class);
    }

    @Test
    void resetStaleDelivering_delegatesToRepository() {
        when(outboxRepository.resetStaleDelivering(any(Instant.class), any(Instant.class)))
                .thenReturn(3);
        scheduler.resetStaleDelivering();
        verify(outboxRepository).resetStaleDelivering(
                eq(fixedClock.instant().minus(Duration.ofMinutes(5))),
                eq(fixedClock.instant()));
    }

    /**
     * Regression: {@link OutboxEntryRepository#resetStaleDelivering} is a
     * {@code @Modifying} JPA query — it MUST run inside a transaction or
     * Hibernate throws {@code TransactionRequiredException} at runtime
     * (not at compile or test time, because mocked repos paper over it).
     * Verifying the {@code txTemplate.execute(...)} call here catches the
     * missing-wrapper regression that surfaced in dev logs every 60s.
     */
    @Test
    void resetStaleDelivering_runsInsideTransaction() {
        when(outboxRepository.resetStaleDelivering(any(Instant.class), any(Instant.class)))
                .thenReturn(3);
        scheduler.resetStaleDelivering();
        verify(txTemplate).execute(any());
    }

    // ── helpers ──

    /**
     * Stage the canonical dispatch path for an outbox row: claim
     * returns {@code id}, readOps returns a snapshot for the dispatch
     * read, the entity is returned for the settle path's
     * {@code outboxRepository.findById(id)} mutation.
     */
    private OutboxEntry stageDispatch(UUID id, int attempts, boolean subEnabled) {
        UUID subId = UUID.randomUUID();
        when(outboxRepository.claimBatch(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of(id));

        OutboxEntrySnapshot rowSnapshot = rowSnapshot(id, subId, attempts);
        when(readOps.outboxSnapshot(id)).thenReturn(Optional.of(rowSnapshot));
        when(readOps.subscriptionSnapshot(subId))
                .thenReturn(Optional.of(subscriptionSnapshot(subId, subEnabled)));

        OutboxEntry entity = rowEntity(id, subId, attempts);
        when(outboxRepository.findById(id)).thenReturn(Optional.of(entity));
        return entity;
    }

    private OutboxEntrySnapshot rowSnapshot(UUID id, UUID subId, int attempts) {
        return new OutboxEntrySnapshot(
                id, subId, UUID.randomUUID(), "api_token.created",
                fixedClock.instant(),
                java.util.Map.of("type", "api_token.created"),
                OutboxStatus.DELIVERING, attempts, fixedClock.instant(),
                null, null, null, null, fixedClock.instant());
    }

    private OutboxEntry rowEntity(UUID id, UUID subId, int attempts) {
        OutboxEntry r = new OutboxEntry();
        r.setId(id);
        r.setSubscriptionId(subId);
        r.setEventId(UUID.randomUUID());
        r.setEventType("api_token.created");
        r.setStatus(OutboxStatus.DELIVERING);
        r.setAttempts(attempts);
        r.setOccurredAt(fixedClock.instant());
        r.setEnvelope(java.util.Map.of("type", "api_token.created"));
        return r;
    }

    private EventSubscriptionSnapshot subscriptionSnapshot(UUID id, boolean enabled) {
        return new EventSubscriptionSnapshot(
                id, "t", null, ChannelType.WEBHOOK,
                java.util.Map.of("url", "https://example.test/"),
                null, enabled, null);
    }

    private static <T> org.mockito.stubbing.Stubber doAnswer(org.mockito.stubbing.Answer<T> ans) {
        return org.mockito.Mockito.doAnswer(ans);
    }
}
