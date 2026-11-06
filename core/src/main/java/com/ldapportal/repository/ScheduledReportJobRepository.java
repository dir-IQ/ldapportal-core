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
     * Disables every job {@code accountId} created. Called before an admin
     * account is deleted: the {@code created_by_admin_id} FK is
     * {@code ON DELETE SET NULL}, and a job with no creator runs unbounded, so
     * leaving it enabled would widen it past the OUs it was created under.
     */
    @Modifying
    @Query("update ScheduledReportJob j set j.enabled = false where j.createdByAdminId = :accountId")
    int disableAllCreatedBy(@Param("accountId") UUID accountId);
}
