// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.SyncReconcileRun;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Persistence for {@link SyncReconcileRun}: the reconcile history of each sync set. */
public interface SyncReconcileRunRepository extends JpaRepository<SyncReconcileRun, UUID> {

    /** How many runs are recorded for a set. */
    long countBySyncSetId(UUID syncSetId);

    /** A set's runs, newest first. */
    List<SyncReconcileRun> findBySyncSetIdOrderByStartedAtDesc(UUID syncSetId, Pageable pageable);

    /** A set's runs with one of {@code triggers}, newest first. */
    List<SyncReconcileRun> findBySyncSetIdAndTriggerInOrderByStartedAtDesc(
            UUID syncSetId, Collection<SyncReconcileTrigger> triggers, Pageable pageable);

    /**
     * Record a running run's progress: entries processed, its phase and the
     * entry it's on. Only while it's RUNNING, so a late write never touches a
     * finished run.
     */
    @Transactional
    @Modifying
    @Query("update SyncReconcileRun r set r.processed = :processed, r.phase = :phase, r.currentDn = :currentDn, "
            + "r.heartbeatAt = :now where r.id = :id "
            + "and r.status = com.ldapportal.entity.enums.SyncReconcileRunStatus.RUNNING")
    int progress(@Param("id") UUID id, @Param("processed") int processed,
                 @Param("phase") com.ldapportal.entity.enums.SyncReconcilePhase phase,
                 @Param("currentDn") String currentDn, @Param("now") OffsetDateTime now);

    /** A set's latest finished complete-scan reconciles, newest first (the not-in-set snapshot comes from the first). */
    @Query("select r from SyncReconcileRun r where r.syncSetId = :setId and r.trigger in :triggers "
            + "and r.status = com.ldapportal.entity.enums.SyncReconcileRunStatus.COMPLETE "
            + "and r.completeScan = true order by r.startedAt desc")
    List<SyncReconcileRun> findCompletedScans(@Param("setId") UUID setId,
                                             @Param("triggers") Collection<SyncReconcileTrigger> triggers,
                                             Pageable pageable);

    /** Drop a set's runs with one of {@code triggers} that started before {@code before} (history pruning). */
    @Transactional
    @Modifying
    @Query("delete from SyncReconcileRun r where r.syncSetId = :setId and r.trigger in :triggers "
            + "and r.startedAt < :before")
    int deleteStartedBefore(@Param("setId") UUID setId,
                            @Param("triggers") Collection<SyncReconcileTrigger> triggers,
                            @Param("before") OffsetDateTime before);
}
