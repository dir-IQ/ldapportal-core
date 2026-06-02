// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReconciliationRunStatus;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;
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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One execution of reconciliation for a {@link ReplicationLink}. Records
 * the trigger, the mode snapshot, the discrepancy counts found, and the
 * lifecycle status. Corrective actions are not stored here — they ride
 * the {@code replication_events} queue (R-P1); per-finding detail lands
 * in {@code reconciliation_findings} in R-P2.
 *
 * <p>See {@code docs/plans/2026-05-31-replication-reconciliation-design.md}.
 */
@Entity
@Table(name = "reconciliation_runs")
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    private ReplicationLink link;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationRunTrigger trigger;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconcileMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationRunStatus status;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "source_entry_count")
    private Integer sourceEntryCount;

    @Column(name = "target_entry_count")
    private Integer targetEntryCount;

    @Column(name = "missing_count", nullable = false)
    private int missingCount = 0;

    @Column(name = "drift_count", nullable = false)
    private int driftCount = 0;

    @Column(name = "extra_count", nullable = false)
    private int extraCount = 0;

    @Column(name = "suppressed_count", nullable = false)
    private int suppressedCount = 0;

    @Column(name = "applied_count", nullable = false)
    private int appliedCount = 0;

    @Column(columnDefinition = "text")
    private String error;
}
