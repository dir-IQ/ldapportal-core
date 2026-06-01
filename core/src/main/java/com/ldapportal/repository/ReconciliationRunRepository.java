// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ReconciliationRun;
import com.ldapportal.entity.enums.ReconciliationRunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
