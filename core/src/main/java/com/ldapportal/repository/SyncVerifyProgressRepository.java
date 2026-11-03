// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.SyncVerifyProgress;
import com.ldapportal.entity.enums.SyncVerifyPhase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Persistence for {@link SyncVerifyProgress}: each sync set's latest content verification. */
public interface SyncVerifyProgressRepository extends JpaRepository<SyncVerifyProgress, UUID> {

    /** Start a new verification on a set whose row exists, replacing the last one. */
    @Transactional
    @Modifying
    @Query("update SyncVerifyProgress p set p.runId = :runId, "
            + "p.status = com.ldapportal.entity.enums.SyncPreviewRunStatus.RUNNING, "
            + "p.phase = com.ldapportal.entity.enums.SyncVerifyPhase.SOURCE, p.processed = 0, p.total = 0, "
            + "p.currentDn = null, p.error = null, p.startedAt = :now, p.heartbeatAt = :now, p.finishedAt = null "
            + "where p.syncSetId = :setId")
    int restart(@Param("setId") UUID setId, @Param("runId") UUID runId, @Param("now") OffsetDateTime now);

    /** Record progress; a no-op once the verification finished or a newer one replaced it. */
    @Transactional
    @Modifying
    @Query("update SyncVerifyProgress p set p.phase = :phase, p.processed = :processed, p.total = :total, "
            + "p.currentDn = :currentDn, p.heartbeatAt = :now "
            + "where p.syncSetId = :setId and p.runId = :runId "
            + "and p.status = com.ldapportal.entity.enums.SyncPreviewRunStatus.RUNNING")
    int progress(@Param("setId") UUID setId, @Param("runId") UUID runId, @Param("phase") SyncVerifyPhase phase,
                 @Param("processed") int processed, @Param("total") int total,
                 @Param("currentDn") String currentDn, @Param("now") OffsetDateTime now);

    /** Mark it finished: COMPLETE, or FAILED with {@code error}. */
    @Transactional
    @Modifying
    @Query("update SyncVerifyProgress p set p.status = :status, "
            + "p.phase = com.ldapportal.entity.enums.SyncVerifyPhase.DONE, p.processed = :processed, "
            + "p.total = :total, p.currentDn = null, p.error = :error, p.heartbeatAt = :now, p.finishedAt = :now "
            + "where p.syncSetId = :setId and p.runId = :runId "
            + "and p.status = com.ldapportal.entity.enums.SyncPreviewRunStatus.RUNNING")
    int finish(@Param("setId") UUID setId, @Param("runId") UUID runId,
               @Param("status") com.ldapportal.entity.enums.SyncPreviewRunStatus status,
               @Param("processed") int processed, @Param("total") int total,
               @Param("error") String error, @Param("now") OffsetDateTime now);
}
