// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.SyncPreviewRunStatus;
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
 * The latest per-entry reconcile preview of one {@link SyncSet}: its progress
 * while it runs and its summary once complete. The planned entries are
 * {@link SyncPreviewEntryRow}s keyed by {@link #runId}.
 *
 * <p>Status transitions are written by targeted updates in
 * {@code SyncPreviewRunRepository} (claim, heartbeat, finish), never by saving
 * a loaded entity, so a worker can't overwrite a newer run.
 */
@Entity
@Table(name = "sync_preview_run")
@Getter
@Setter
@NoArgsConstructor
public class SyncPreviewRun {

    @Id
    @Column(name = "sync_set_id")
    private UUID syncSetId;

    /** Changes with every run; entries carry it. */
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SyncPreviewRunStatus status;

    @Column(name = "requested_by")
    private String requestedBy;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    /** Refreshed while the run makes progress; a stale one means its server stopped. */
    @Column(name = "heartbeat_at", nullable = false)
    private OffsetDateTime heartbeatAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /** Entries dry-run so far. */
    @Column(name = "processed", nullable = false)
    private int processed;

    /** Entries to dry-run (known once the source scan is planned). */
    @Column(name = "total", nullable = false)
    private int total;

    /** The entry being dry-run now (its source DN, or its target DN for an entry gone from the source). */
    @Column(name = "current_dn", columnDefinition = "TEXT")
    private String currentDn;

    @Column(name = "source_count", nullable = false)
    private int sourceCount;

    @Column(name = "managed_count", nullable = false)
    private long managedCount;

    /** Entries the "changes only" view lists. */
    @Column(name = "actionable_count", nullable = false)
    private long actionableCount;

    @Column(name = "guard_tripped", nullable = false)
    private boolean guardTripped;

    @Column(name = "guard_reason", columnDefinition = "TEXT")
    private String guardReason;

    @Column(name = "complete_scan", nullable = false)
    private boolean completeScan;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /** The link's source directory when the run completed (an edit to the link outdates it). */
    @Column(name = "source_dir_id")
    private UUID sourceDirId;

    /** The link's target directory when the run completed. */
    @Column(name = "target_dir_id")
    private UUID targetDirId;
}
