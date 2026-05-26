// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import com.ldapportal.core.events.channel.DeliveryOutcome;
import com.ldapportal.core.events.channel.OutboundChannel;
import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
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
    @Mock private EventSubscriptionRepository subscriptionRepository;
    @Mock private OutboundChannel channel;
    @Mock private TransactionTemplate txTemplate;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-04-23T10:00:00Z"), ZoneOffset.UTC);

    private OutboundDispatcherScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(channel.type()).thenReturn(ChannelType.WEBHOOK);
        scheduler = new OutboundDispatcherScheduler(
                outboxRepository, subscriptionRepository,
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
        when(outboxRepository.claimBatch(eq("PENDING"), any(Instant.class), anyInt()))
                .thenReturn(List.of(id));

        OutboxEntry row = rowForDispatch(id);
        when(outboxRepository.findById(id)).thenReturn(Optional.of(row));
        when(subscriptionRepository.findById(row.getSubscriptionId()))
                .thenReturn(Optional.of(subscription()));
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
        when(outboxRepository.claimBatch(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of(id));

        OutboxEntry row = rowForDispatch(id);
        row.setAttempts(1);       // first attempt just happened (claim incremented it)
        when(outboxRepository.findById(id)).thenReturn(Optional.of(row));
        when(subscriptionRepository.findById(row.getSubscriptionId()))
                .thenReturn(Optional.of(subscription()));
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
        when(outboxRepository.claimBatch(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of(id));

        OutboxEntry row = rowForDispatch(id);
        row.setAttempts(1);
        when(outboxRepository.findById(id)).thenReturn(Optional.of(row));
        when(subscriptionRepository.findById(row.getSubscriptionId()))
                .thenReturn(Optional.of(subscription()));
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
        when(outboxRepository.claimBatch(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of(id));

        OutboxEntry row = rowForDispatch(id);
        row.setAttempts(6);     // BACKOFF.length + 1; this is the final attempt
        when(outboxRepository.findById(id)).thenReturn(Optional.of(row));
        when(subscriptionRepository.findById(row.getSubscriptionId()))
                .thenReturn(Optional.of(subscription()));
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
        when(outboxRepository.claimBatch(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of(id));

        OutboxEntry row = rowForDispatch(id);
        row.setAttempts(1);
        when(outboxRepository.findById(id)).thenReturn(Optional.of(row));
        when(subscriptionRepository.findById(row.getSubscriptionId()))
                .thenReturn(Optional.of(subscription()));
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
        when(outboxRepository.claimBatch(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of(id));

        OutboxEntry row = rowForDispatch(id);
        EventSubscription disabled = subscription();
        disabled.setEnabled(false);
        when(outboxRepository.findById(id)).thenReturn(Optional.of(row));
        when(subscriptionRepository.findById(row.getSubscriptionId()))
                .thenReturn(Optional.of(disabled));

        scheduler.dispatch();

        verify(channel, never()).deliver(any(), any());
        ArgumentCaptor<OutboxEntry> cap = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
    }

    @Test
    void dispatch_deletedSubscription_deadLetters() {
        UUID id = UUID.randomUUID();
        when(outboxRepository.claimBatch(anyString(), any(Instant.class), anyInt()))
                .thenReturn(List.of(id));

        OutboxEntry row = rowForDispatch(id);
        when(outboxRepository.findById(id)).thenReturn(Optional.of(row));
        when(subscriptionRepository.findById(row.getSubscriptionId()))
                .thenReturn(Optional.empty());

        scheduler.dispatch();

        verify(channel, never()).deliver(any(), any());
        ArgumentCaptor<OutboxEntry> cap = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(OutboxStatus.DEAD_LETTERED);
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

    private OutboxEntry rowForDispatch(UUID id) {
        OutboxEntry r = new OutboxEntry();
        r.setId(id);
        r.setSubscriptionId(UUID.randomUUID());
        r.setEventId(UUID.randomUUID());
        r.setEventType("api_token.created");
        r.setStatus(OutboxStatus.DELIVERING);
        r.setAttempts(1);
        r.setOccurredAt(fixedClock.instant());
        r.setEnvelope(java.util.Map.of("type", "api_token.created"));
        return r;
    }

    private EventSubscription subscription() {
        EventSubscription s = new EventSubscription();
        s.setId(UUID.randomUUID());
        s.setName("t");
        s.setChannelType(ChannelType.WEBHOOK);
        s.setEnabled(true);
        return s;
    }

    private static <T> org.mockito.stubbing.Stubber doAnswer(org.mockito.stubbing.Answer<T> ans) {
        return org.mockito.Mockito.doAnswer(ans);
    }
}
