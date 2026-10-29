// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.SyncPreviewRunStatus;
import com.ldapportal.entity.enums.SyncVerifyPhase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The latest content verification of a sync set, as it runs and how it ended,
 * so the inventory can show its progress. One row per set, replaced by each
 * new verification.
 */
@Entity
@Table(name = "sync_verify_progress")
@Getter
@Setter
@NoArgsConstructor
public class SyncVerifyProgress {

    @Id
    @Column(name = "sync_set_id")
    private UUID syncSetId;

    /** Changes with every verification. */
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SyncPreviewRunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false)
    private SyncVerifyPhase phase;

    /** Entries read (source / target phases) or compared so far. */
    @Column(name = "processed", nullable = false)
    private int processed;

    /** Entries to compare, once both sides are read ({@code 0} before). */
    @Column(name = "total", nullable = false)
    private int total;

    /** The entry being compared now, while COMPARING. */
    @Column(name = "current_dn", columnDefinition = "TEXT")
    private String currentDn;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    /** Refreshed while it makes progress; a stale one means its server stopped. */
    @Column(name = "heartbeat_at", nullable = false)
    private OffsetDateTime heartbeatAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;
}
