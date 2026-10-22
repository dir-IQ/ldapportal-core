// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.SyncReconcileRunStatus;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
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
 * One reconcile run of a {@link SyncSet}: when and why it ran, and what it did
 * to the entries it processed. Only the reconcile that owns the row writes it,
 * so it is saved as an entity rather than through conditional updates.
 *
 * <p>The outcome counters use the same vocabulary as the per-entry preview
 * ({@code SyncPreviewAction}), so a preview and the run that follows it can be
 * compared directly.
 */
@Entity
@Table(name = "sync_reconcile_run")
@Getter
@Setter
@NoArgsConstructor
public class SyncReconcileRun {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "sync_set_id", nullable = false)
    private UUID syncSetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private SyncReconcileTrigger trigger;

    /** The operator who started a MANUAL run. */
    @Column(name = "requested_by", columnDefinition = "TEXT")
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SyncReconcileRunStatus status;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    /** Refreshed while the run makes progress; a stale one means its server stopped. */
    @Column(name = "heartbeat_at", nullable = false)
    private OffsetDateTime heartbeatAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /** False when source enumeration failed or stopped, so the not-seen sweep (deletes) was skipped. */
    @Column(name = "complete_scan", nullable = false)
    private boolean completeScan;

    /** Source entries the run planned (enumerated, with an identity). */
    @Column(name = "source_count", nullable = false)
    private int sourceCount;

    /** Index rows the set managed when the run started. */
    @Column(name = "managed_before", nullable = false)
    private long managedBefore;

    /** Index rows the set managed when the run finished; null until then. */
    @Column(name = "managed_after")
    private Long managedAfter;

    /** Entries recomputed so far (planned entries plus not-seen rows). */
    @Column(name = "processed", nullable = false)
    private int processed;

    @Column(name = "added", nullable = false)
    private int added;

    @Column(name = "modified", nullable = false)
    private int modified;

    @Column(name = "moved", nullable = false)
    private int moved;

    @Column(name = "deleted", nullable = false)
    private int deleted;

    @Column(name = "held_for_review", nullable = false)
    private int heldForReview;

    /** Applied without a target write: the target already matched (e.g. an adopted entry). */
    @Column(name = "no_change", nullable = false)
    private int noChange;

    /** Stale index rows dropped because another identity owns their target entry. */
    @Column(name = "forgotten", nullable = false)
    private int forgotten;

    @Column(name = "unchanged", nullable = false)
    private int unchanged;

    /** Entries whose target write failed (their rows went FAILED). */
    @Column(name = "failed", nullable = false)
    private int failed;

    /** Entries whose recompute threw; they retry on the next run. */
    @Column(name = "faulted", nullable = false)
    private int faulted;

    /** Entries not processed because the set or link was disabled during the run. */
    @Column(name = "skipped", nullable = false)
    private int skipped;

    @Column(name = "guard_tripped", nullable = false)
    private boolean guardTripped;

    @Column(name = "guard_reason", columnDefinition = "TEXT")
    private String guardReason;

    /** Why the run aborted, when FAILED. */
    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /** The app instance (host) that ran it. */
    @Column(name = "instance_name", columnDefinition = "TEXT")
    private String instanceName;

    /** When the source scan and plan finished; {@code null} until then. */
    @Column(name = "planned_at")
    private OffsetDateTime plannedAt;

    /** The set's settings and delete-guard limits at run time, as JSON. */
    @Column(name = "settings", columnDefinition = "TEXT")
    private String settings;

    /** Failed and faulted entries grouped by reason, as JSON. */
    @Column(name = "failure_reasons", columnDefinition = "TEXT")
    private String failureReasons;

    /** Sample target DNs per outcome, as JSON. */
    @Column(name = "samples", columnDefinition = "TEXT")
    private String samples;

    /** When the post-reconcile content verification ran; {@code null} when none was recorded. */
    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "verify_missing")
    private Integer verifyMissing;

    @Column(name = "verify_orphan")
    private Integer verifyOrphan;

    @Column(name = "verify_mismatch")
    private Integer verifyMismatch;

    /** Whether every entry's DN was kept (the set's record-all-DNs flag at run time). */
    @Column(name = "all_dns_recorded", nullable = false)
    private boolean allDnsRecorded;
}
