// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ReconciliationRun;
import com.ldapportal.entity.enums.ReconciliationRunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

    /** Run history for a link, newest first — backs the runs list endpoint. */
    Page<ReconciliationRun> findByLinkIdOrderByStartedAtDesc(UUID linkId, Pageable pageable);

    /** Whether a link currently has a live (RUNNING) run — single-flight check. */
    boolean existsByLinkIdAndStatus(UUID linkId, ReconciliationRunStatus status);

    /**
     * RUNNING runs claimed before {@code threshold} — left behind by a
     * crashed worker. The stale-run sweep flips them to FAILED so the link
     * reschedules. Mirrors {@code ReplicationWorker.resetStaleInFlight}.
     */
    List<ReconciliationRun> findByStatusAndClaimedAtBefore(
            ReconciliationRunStatus status, OffsetDateTime threshold);

    // ── Retention ───────────────────────────────────────────────────────────

    /**
     * Retention sweep: hard-delete finished runs older than {@code cutoff}
     * whose findings are all resolved. The {@code run_id} FK on
     * {@code reconciliation_findings} is {@code ON DELETE CASCADE}, so the
     * run's (resolved) findings go with it — no separate finding sweep
     * needed.
     *
     * <p>Runs that still carry a {@code PROPOSED} finding are spared so a
     * slow-to-triage operator never loses pending review work to the clock;
     * once those findings are applied/dismissed the run becomes eligible on
     * the next sweep. The cutoff is computed in Java so the bulk DELETE is
     * plain JPQL and runs identically on H2 and Postgres. Self-transactional
     * — called directly from {@code ReconciliationRetentionScheduler}.
     */
    @Transactional
    @Modifying
    @Query("""
        DELETE FROM ReconciliationRun r
         WHERE r.finishedAt IS NOT NULL
           AND r.finishedAt < :cutoff
           AND r.id NOT IN (
               SELECT f.run.id FROM ReconciliationFinding f
                WHERE f.status = com.ldapportal.entity.enums.ReconciliationFindingStatus.PROPOSED)
        """)
    int deleteFinishedWithoutOpenFindingsBefore(@Param("cutoff") OffsetDateTime cutoff);
}
