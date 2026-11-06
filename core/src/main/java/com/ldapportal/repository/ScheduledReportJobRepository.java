// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.core.reports.schedule.ReportJobRunStatus;
import com.ldapportal.entity.ScheduledReportJob;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link ScheduledReportJob}. List/get operations are
 * directory-scoped; the scheduler loads enabled jobs and computes due-ness in
 * Java from {@code cron_expression} + {@code last_run_at} (the table has no
 * {@code next_run_at} column). The dashboard health tile uses the count queries.
 */
@Repository
public interface ScheduledReportJobRepository extends JpaRepository<ScheduledReportJob, UUID> {

    Page<ScheduledReportJob> findAllByDirectoryId(UUID directoryId, Pageable pageable);

    Optional<ScheduledReportJob> findByIdAndDirectoryId(UUID id, UUID directoryId);

    /** Enabled jobs across all directories — the scheduler's poll set. */
    List<ScheduledReportJob> findAllByEnabledTrue();

    long countByEnabledTrue();

    long countByEnabledTrueAndLastRunStatus(ReportJobRunStatus status);

    /**
     * Claims a job's run by moving {@code last_run_at} from the value the caller
     * read ({@code seen}) to {@code now}, in one conditional update. Every
     * backend instance polls the same rows, so this compare-and-set is what
     * stops two of them (or a poll and a run-now) delivering the same run: only
     * the caller whose update hits the row may run it; anyone holding an older
     * {@code seen} gets 0 and must skip.
     *
     * @return 1 when the claim was won, 0 when someone else already moved the row
     */
    @Transactional
    @Modifying
    @Query("update ScheduledReportJob j set j.lastRunAt = :now where j.id = :id and j.lastRunAt = :seen")
    int claimRun(@Param("id") UUID id, @Param("seen") OffsetDateTime seen, @Param("now") OffsetDateTime now);

    /** {@link #claimRun} for a job that has never run ({@code last_run_at} still null). */
    @Transactional
    @Modifying
    @Query("update ScheduledReportJob j set j.lastRunAt = :now where j.id = :id and j.lastRunAt is null")
    int claimFirstRun(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}
