// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import com.ldapportal.core.events.channel.DeliveryOutcome;
import com.ldapportal.core.events.channel.OutboundChannel;
import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Drives the outbox → channel pipeline. Three responsibilities:
 * <ol>
 *   <li>{@link #dispatch()} — every 5s, claim PENDING rows ready to go,
 *       hand each to the matching channel, update status based on outcome.</li>
 *   <li>{@link #resetStaleDelivering()} — every 60s, reset rows stuck in
 *       DELIVERING longer than 5 minutes (crash recovery).</li>
 * </ol>
 *
 * <p>Single-instance assumption: two dispatchers running concurrently would
 * double-dispatch. HA is Phase 11's problem. SKIP LOCKED gives us the right
 * concurrency primitive when HA lands.</p>
 */
@Component
@Slf4j
public class OutboundDispatcherScheduler {

    private static final int BATCH_SIZE = 50;
    private static final Duration MAX_DELIVERING = Duration.ofMinutes(5);
    static final Duration[] BACKOFF = {
        Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
        Duration.ofHours(1),   Duration.ofHours(6)
    };
    private static final int MAX_ATTEMPTS = BACKOFF.length + 1;
    private static final Random JITTER = new Random();

    private final OutboxEntryRepository outboxRepository;
    private final EventSubscriptionRepository subscriptionRepository;
    private final List<OutboundChannel> channels;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public OutboundDispatcherScheduler(OutboxEntryRepository outboxRepository,
                                       EventSubscriptionRepository subscriptionRepository,
                                       List<OutboundChannel> channels,
                                       TransactionTemplate txTemplate,
                                       Clock clock) {
        this.outboxRepository = outboxRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.channels = channels;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 5_000)
    public void dispatch() {
        List<UUID> claimed = txTemplate.execute(status ->
            outboxRepository.claimBatch(
                    OutboxStatus.PENDING.name(), clock.instant(), BATCH_SIZE));
        if (claimed == null || claimed.isEmpty()) return;
        for (UUID id : claimed) {
            try {
                deliverAndResolve(id);
            } catch (RuntimeException e) {
                log.error("dispatch loop caught unexpected error for id={}", id, e);
            }
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void resetStaleDelivering() {
        // resetStaleDelivering is a @Modifying JPA query — it must run inside
        // a transaction or Hibernate throws TransactionRequiredException. Wrap
        // identically to dispatch()'s claimBatch call.
        Integer n = txTemplate.execute(status ->
                outboxRepository.resetStaleDelivering(
                        clock.instant().minus(MAX_DELIVERING), clock.instant()));
        if (n != null && n > 0) log.warn("outbox.stale_reset count={}", n);
    }

    private void deliverAndResolve(UUID id) {
        OutboxEntry row = outboxRepository.findById(id).orElse(null);
        if (row == null) return;
        EventSubscription sub = subscriptionRepository
                .findById(row.getSubscriptionId()).orElse(null);

        if (sub == null || !sub.isEnabled()) {
            txTemplate.executeWithoutResult(s -> markDeadLettered(
                    id, "subscription disabled or deleted", null));
            return;
        }

        OutboundChannel channel = channels.stream()
                .filter(c -> c.type() == sub.getChannelType()).findFirst()
                .orElse(null);
        if (channel == null) {
            log.error("no channel registered for type={}", sub.getChannelType());
            txTemplate.executeWithoutResult(s -> markDeadLettered(
                    id, "no channel registered for " + sub.getChannelType(), null));
            return;
        }

        DeliveryOutcome outcome;
        try {
            outcome = channel.deliver(row, sub);
        } catch (RuntimeException e) {
            outcome = DeliveryOutcome.transientFailure(
                    "uncaught: " + e.getMessage(), null);
        }
        final DeliveryOutcome finalOutcome = outcome;
        txTemplate.executeWithoutResult(s -> resolveRow(id, finalOutcome));
    }

    private void resolveRow(UUID id, DeliveryOutcome outcome) {
        OutboxEntry row = outboxRepository.findById(id).orElse(null);
        if (row == null) return;
        switch (outcome.kind()) {
            case SUCCESS -> {
                row.setStatus(OutboxStatus.DELIVERED);
                row.setDeliveredAt(clock.instant());
                row.setLastError(null);
                row.setLastHttpStatus(outcome.httpStatus());
                log.debug("outbox.delivered id={} type={} http={}",
                        id, row.getEventType(), outcome.httpStatus());
            }
            case TRANSIENT_FAILURE -> {
                if (row.getAttempts() >= MAX_ATTEMPTS) {
                    row.setStatus(OutboxStatus.DEAD_LETTERED);
                    row.setDeadLetteredAt(clock.instant());
                    row.setLastError(outcome.message());
                    row.setLastHttpStatus(outcome.httpStatus());
                    log.warn("outbox.dead_lettered id={} reason={}", id, outcome.message());
                } else {
                    Duration base = BACKOFF[row.getAttempts() - 1];
                    row.setStatus(OutboxStatus.PENDING);
                    row.setNextAttemptAt(clock.instant().plus(withJitter(base)));
                    row.setLastError(outcome.message());
                    row.setLastHttpStatus(outcome.httpStatus());
                    log.info("outbox.retry id={} attempts={} reason=\"{}\"",
                            id, row.getAttempts(), outcome.message());
                }
            }
            case PERMANENT_FAILURE -> {
                row.setStatus(OutboxStatus.DEAD_LETTERED);
                row.setDeadLetteredAt(clock.instant());
                row.setLastError(outcome.message());
                row.setLastHttpStatus(outcome.httpStatus());
                log.warn("outbox.dead_lettered id={} reason={}", id, outcome.message());
            }
        }
        outboxRepository.save(row);
    }

    private void markDeadLettered(UUID id, String reason, Integer httpStatus) {
        OutboxEntry row = outboxRepository.findById(id).orElse(null);
        if (row == null) return;
        row.setStatus(OutboxStatus.DEAD_LETTERED);
        row.setDeadLetteredAt(clock.instant());
        row.setLastError(reason);
        row.setLastHttpStatus(httpStatus);
        outboxRepository.save(row);
        log.warn("outbox.dead_lettered id={} reason={}", id, reason);
    }

    private Duration withJitter(Duration base) {
        long ms = base.toMillis();
        long delta = (long) (ms * 0.2 * JITTER.nextDouble());
        return Duration.ofMillis(ms + delta);
    }
}
