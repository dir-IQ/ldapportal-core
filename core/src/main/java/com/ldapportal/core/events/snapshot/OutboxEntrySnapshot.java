// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.snapshot;

import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.OutboxStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable projection of an {@link OutboxEntry} for the dispatch
 * path. Carries every field the dispatcher and channel implementations
 * need (envelope, eventId, eventType, occurredAt) so the entity itself
 * never crosses out of the read-side transaction.
 *
 * <p>Same design pattern as
 * {@code com.ldapportal.ldap.replication.ReplicationEventSnapshot} —
 * snapshots eliminate the {@code LazyInitializationException} bug class
 * for non-transactional consumers (the {@code @Scheduled} dispatcher,
 * the channel SPI implementations) by ensuring no JPA proxy reference
 * is ever in scope after the read tx commits. {@link OutboxEntry}
 * itself currently has no LAZY associations, but the same can't be
 * said for {@code EventSubscription} (one LAZY {@code createdBy}),
 * and keeping the boundary types consistent lets the channel SPI
 * accept either side safely.
 *
 * <p>The dispatcher's settle paths (mark-delivered / retry / dead-
 * letter / mark-stale-reset) continue to mutate the entity inside
 * a {@code TransactionTemplate} block — those are reads + writes
 * inside a tx, no boundary crossing involved, so the entity stays
 * the right type there.
 */
public record OutboxEntrySnapshot(
        UUID id,
        UUID subscriptionId,
        UUID eventId,
        String eventType,
        Instant occurredAt,
        Map<String, Object> envelope,
        OutboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        Integer lastHttpStatus,
        Instant deliveredAt,
        Instant deadLetteredAt,
        Instant createdAt) {

    /**
     * Materialise an entity into a snapshot. {@link OutboxEntry} has
     * no LAZY associations today, so this factory is safe to call
     * from any context — but the read-side service still wraps it
     * in {@code @Transactional(readOnly=true)} for consistency with
     * the {@link EventSubscriptionSnapshot} side and to keep the
     * pattern uniform if a LAZY field is added later.
     */
    public static OutboxEntrySnapshot from(OutboxEntry e) {
        return new OutboxEntrySnapshot(
                e.getId(),
                e.getSubscriptionId(),
                e.getEventId(),
                e.getEventType(),
                e.getOccurredAt(),
                e.getEnvelope(),
                e.getStatus(),
                e.getAttempts(),
                e.getNextAttemptAt(),
                e.getLastError(),
                e.getLastHttpStatus(),
                e.getDeliveredAt(),
                e.getDeadLetteredAt(),
                e.getCreatedAt());
    }
}
