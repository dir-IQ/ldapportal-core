// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.SyncPreviewRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persistence for {@link SyncPreviewRun}. Every status transition is a
 * conditional update that names the run it applies to, so a worker whose run
 * was superseded (or reclaimed after its server stopped) changes nothing.
 */
public interface SyncPreviewRunRepository extends JpaRepository<SyncPreviewRun, UUID> {

    /**
     * Atomically start a new run for a set whose row exists: succeeds unless a
     * run is RUNNING with a heartbeat newer than {@code staleBefore}. Resets the
     * progress and summary. Returns 1 if claimed, 0 otherwise.
     */
    @Transactional
    @Modifying
    @Query("update SyncPreviewRun r set r.runId = :runId, "
            + "r.status = com.ldapportal.entity.enums.SyncPreviewRunStatus.RUNNING, "
            + "r.requestedBy = :requestedBy, r.startedAt = :now, r.heartbeatAt = :now, r.finishedAt = null, "
            + "r.processed = 0, r.total = 0, r.sourceCount = 0, r.managedCount = 0, r.actionableCount = 0, "
            + "r.guardTripped = false, r.guardReason = null, r.completeScan = false, r.note = null, r.error = null, "
            + "r.sourceDirId = null, r.targetDirId = null "
            + "where r.syncSetId = :setId and (r.status <> com.ldapportal.entity.enums.SyncPreviewRunStatus.RUNNING "
            + "or r.heartbeatAt < :staleBefore)")
    int claim(@Param("setId") UUID setId, @Param("runId") UUID runId, @Param("requestedBy") String requestedBy,
              @Param("now") OffsetDateTime now, @Param("staleBefore") OffsetDateTime staleBefore);

    /** Keep a running run alive. Returns 0 when the run is no longer current. */
    @Transactional
    @Modifying
    @Query("update SyncPreviewRun r set r.heartbeatAt = :now where r.syncSetId = :setId and r.runId = :runId "
            + "and r.status = com.ldapportal.entity.enums.SyncPreviewRunStatus.RUNNING")
    int heartbeat(@Param("setId") UUID setId, @Param("runId") UUID runId, @Param("now") OffsetDateTime now);

    /** Record progress. Returns 0 when the run is no longer current (the worker should stop). */
    @Transactional
    @Modifying
    @Query("update SyncPreviewRun r set r.processed = :processed, r.total = :total, r.heartbeatAt = :now "
            + "where r.syncSetId = :setId and r.runId = :runId "
            + "and r.status = com.ldapportal.entity.enums.SyncPreviewRunStatus.RUNNING")
    int progress(@Param("setId") UUID setId, @Param("runId") UUID runId, @Param("processed") int processed,
                 @Param("total") int total, @Param("now") OffsetDateTime now);

    /** Mark the run complete with its summary. Returns 0 when it is no longer current. */
    @Transactional
    @Modifying
    @Query("update SyncPreviewRun r set r.status = com.ldapportal.entity.enums.SyncPreviewRunStatus.COMPLETE, "
            + "r.finishedAt = :now, r.heartbeatAt = :now, r.processed = :total, r.total = :total, "
            + "r.sourceCount = :sourceCount, r.managedCount = :managedCount, r.actionableCount = :actionableCount, "
            + "r.guardTripped = :guardTripped, r.guardReason = :guardReason, r.completeScan = :completeScan, "
            + "r.note = :note, r.sourceDirId = :sourceDirId, r.targetDirId = :targetDirId "
            + "where r.syncSetId = :setId and r.runId = :runId "
            + "and r.status = com.ldapportal.entity.enums.SyncPreviewRunStatus.RUNNING")
    int complete(@Param("setId") UUID setId, @Param("runId") UUID runId, @Param("now") OffsetDateTime now,
                 @Param("total") int total, @Param("sourceCount") int sourceCount,
                 @Param("managedCount") long managedCount, @Param("actionableCount") long actionableCount,
                 @Param("guardTripped") boolean guardTripped, @Param("guardReason") String guardReason,
                 @Param("completeScan") boolean completeScan, @Param("note") String note,
                 @Param("sourceDirId") UUID sourceDirId, @Param("targetDirId") UUID targetDirId);

    /** Mark the run failed. Returns 0 when it is no longer current. */
    @Transactional
    @Modifying
    @Query("update SyncPreviewRun r set r.status = com.ldapportal.entity.enums.SyncPreviewRunStatus.FAILED, "
            + "r.finishedAt = :now, r.heartbeatAt = :now, r.error = :error "
            + "where r.syncSetId = :setId and r.runId = :runId "
            + "and r.status = com.ldapportal.entity.enums.SyncPreviewRunStatus.RUNNING")
    int fail(@Param("setId") UUID setId, @Param("runId") UUID runId, @Param("now") OffsetDateTime now,
             @Param("error") String error);

    /**
     * Remove runs last active before {@code before}: finished ones past the
     * retention, and running ones whose server stopped long ago. A live run
     * heartbeats, so it is never old. Delete their entries first.
     */
    @Transactional
    @Modifying
    @Query("delete from SyncPreviewRun r where r.heartbeatAt < :before")
    int deleteInactiveSince(@Param("before") OffsetDateTime before);
}
