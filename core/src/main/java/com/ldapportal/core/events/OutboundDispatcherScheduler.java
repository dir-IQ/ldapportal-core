// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import com.ldapportal.core.events.channel.DeliveryOutcome;
import com.ldapportal.core.events.channel.OutboundChannel;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
import com.ldapportal.core.events.snapshot.EventSubscriptionSnapshot;
import com.ldapportal.core.events.snapshot.OutboxEntrySnapshot;
import com.ldapportal.core.util.BackoffPolicies;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
 *
 * <p><b>Snapshot-based read boundary.</b> The dispatcher reads outbox
 * rows and subscriptions via {@link OutboundEventReadOps}, which
 * returns immutable {@link OutboxEntrySnapshot} /
 * {@link EventSubscriptionSnapshot} records. Channel implementations
 * receive snapshots, not entities — so accidental access to a LAZY
 * association ({@code subscription.createdBy}, or anything added later)
 * cannot silently fail through the dispatcher's transient-failure
 * catch and trigger a retry loop for no real-world reason. Mirrors
 * the architecture of {@code com.ldapportal.ldap.replication}.
 *
 * <p>The settle paths ({@link #resolveRow}, {@link #markDeadLettered})
 * continue to load the entity inside a {@code TransactionTemplate}
 * block and mutate-then-save it. That's a load + mutate + flush all
 * inside one tx — the entity stays attached the whole time, no
 * boundary crossing involved.
 */
@Component
@Slf4j
public class OutboundDispatcherScheduler {

    private static final int BATCH_SIZE = 50;
    private static final Duration MAX_DELIVERING = Duration.ofMinutes(5);
    private static final Random JITTER = new Random();

    private final OutboxEntryRepository outboxRepository;
    private final OutboundEventReadOps readOps;
    private final List<OutboundChannel> channels;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public OutboundDispatcherScheduler(OutboxEntryRepository outboxRepository,
                                       OutboundEventReadOps readOps,
                                       List<OutboundChannel> channels,
                                       TransactionTemplate txTemplate,
                                       Clock clock) {
        this.outboxRepository = outboxRepository;
        this.readOps = readOps;
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
        // Read both projections via ReadOps so the entity never escapes
        // the read tx — channels see immutable records, no JPA proxies
        // in scope, no possibility of LazyInitializationException
        // leaking into the dispatcher's generic transient-failure catch.
        Optional<OutboxEntrySnapshot> rowOpt = readOps.outboxSnapshot(id);
        if (rowOpt.isEmpty()) return;
        OutboxEntrySnapshot row = rowOpt.get();

        EventSubscriptionSnapshot sub = readOps.subscriptionSnapshot(row.subscriptionId())
                .orElse(null);

        if (sub == null || !sub.enabled()) {
            txTemplate.executeWithoutResult(s -> markDeadLettered(
                    id, "subscription disabled or deleted", null));
            return;
        }

        OutboundChannel channel = channels.stream()
                .filter(c -> c.type() == sub.channelType()).findFirst()
                .orElse(null);
        if (channel == null) {
            log.error("no channel registered for type={}", sub.channelType());
            txTemplate.executeWithoutResult(s -> markDeadLettered(
                    id, "no channel registered for " + sub.channelType(), null));
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
        // Status guard: the stale-reset sweep (or another dispatcher
        // in a future HA world) may have revoked our claim while the
        // HTTP POST was in flight. If the row is no longer DELIVERING,
        // the new owner's verdict — not ours — is what counts. Drop
        // the result rather than overwrite their state.
        if (row.getStatus() != OutboxStatus.DELIVERING) {
            log.warn("outbox.settle_skipped id={} reason=\"claim revoked, current status {}\"",
                    id, row.getStatus());
            return;
        }
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
                // The shared OUTBOUND_EVENTS ladder has 5 rungs; an empty
                // delay means the retry budget is exhausted (attempts past
                // the last rung) and the row dead-letters. Jitter is applied
                // here, at the call site — it's a delivery concern, not part
                // of the nominal schedule the policy describes.
                Optional<Duration> base =
                        BackoffPolicies.OUTBOUND_EVENTS.delayForAttempt(row.getAttempts());
                if (base.isEmpty()) {
                    row.setStatus(OutboxStatus.DEAD_LETTERED);
                    row.setDeadLetteredAt(clock.instant());
                    row.setLastError(outcome.message());
                    row.setLastHttpStatus(outcome.httpStatus());
                    log.warn("outbox.dead_lettered id={} reason={}", id, outcome.message());
                } else {
                    row.setStatus(OutboxStatus.PENDING);
                    row.setNextAttemptAt(clock.instant().plus(withJitter(base.get())));
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
        // Same status guard as resolveRow — don't overwrite a row whose
        // claim has been revoked. If a different owner subsequently picks
        // it up and finds the subscription is back / the channel is now
        // registered, they're free to make their own verdict.
        if (row.getStatus() != OutboxStatus.DELIVERING) {
            log.warn("outbox.dead_letter_skipped id={} reason=\"claim revoked, current status {}\"",
                    id, row.getStatus());
            return;
        }
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
