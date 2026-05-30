// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.entity.enums.ReplicationOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One pending / in-flight / completed replication event. Durable queue
 * row drained by {@code ReplicationWorker} in P1.
 *
 * <p>{@link #payload} is a JSONB map whose shape depends on
 * {@link #operation}; see {@code ReplicationOperation} for the
 * in-memory value class the enqueuer builds from a captured LDAP write
 * before persisting here.
 */
@Entity
@Table(name = "replication_events")
@Getter
@Setter
@NoArgsConstructor
public class ReplicationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    private ReplicationLink link;

    @Enumerated(EnumType.STRING)
    @Column(name = "enqueue_source", nullable = false, length = 20)
    private ReplicationEnqueueSource enqueueSource = ReplicationEnqueueSource.APP_INTERCEPT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReplicationOperationType operation;

    @Column(name = "source_dn", nullable = false, length = 2000)
    private String sourceDn;

    @Column(name = "target_dn", nullable = false, length = 2000)
    private String targetDn;

    /**
     * Mapped, ready-to-apply payload. Keys depend on {@link #operation}:
     * <ul>
     *   <li>{@code ADD}       — {@code attributes}: Map&lt;String,List&lt;String&gt;&gt;</li>
     *   <li>{@code MODIFY}    — {@code modifications}: List&lt;Map&gt; with
     *       {@code type} (REPLACE/ADD/DELETE), {@code name}, {@code values}</li>
     *   <li>{@code DELETE}    — empty</li>
     *   <li>{@code MODIFY_DN} — {@code newRdn}, {@code deleteOldRdn},
     *       {@code newSuperiorDn}</li>
     * </ul>
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReplicationEventStatus status = ReplicationEventStatus.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(name = "enqueued_at", nullable = false, updatable = false)
    private OffsetDateTime enqueuedAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;
}
