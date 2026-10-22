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

    /** Drop a set's runs with one of {@code triggers} that started before {@code before} (history pruning). */
    @Transactional
    @Modifying
    @Query("delete from SyncReconcileRun r where r.syncSetId = :setId and r.trigger in :triggers "
            + "and r.startedAt < :before")
    int deleteStartedBefore(@Param("setId") UUID setId,
                            @Param("triggers") Collection<SyncReconcileTrigger> triggers,
                            @Param("before") OffsetDateTime before);
}
