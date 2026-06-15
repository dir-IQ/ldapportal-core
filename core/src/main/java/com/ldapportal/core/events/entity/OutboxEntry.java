// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.entity;

import com.ldapportal.core.events.enums.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row per (event, subscription) pair. Independent retry state per row.
 *
 * <p>Lifecycle: PENDING → DELIVERING → {DELIVERED, PENDING+backoff, DEAD_LETTERED}.
 * See {@link OutboxStatus}.</p>
 */
@Entity
@Table(name = "event_outbox")
@Getter
@Setter
@NoArgsConstructor
public class OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Full envelope JSON. Exact UTF-8 bytes are what gets POSTed as body. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> envelope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    /**
     * When the row last transitioned to DELIVERING, or NULL if never
     * claimed. Drives the stale-reset sweep — using {@code nextAttemptAt}
     * for that purpose false-resets backlog-drain claims (the row's
     * next_attempt_at can be far in the past at claim time when the
     * dispatcher was down or the backoff already elapsed).
     */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
