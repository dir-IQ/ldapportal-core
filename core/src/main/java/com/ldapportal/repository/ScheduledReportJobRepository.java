// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.core.reports.schedule.ReportJobRunStatus;
import com.ldapportal.entity.ScheduledReportJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
