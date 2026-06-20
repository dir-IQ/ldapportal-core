// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.dashboard;

import com.ldapportal.core.reports.schedule.ReportJobRunStatus;
import com.ldapportal.repository.ScheduledReportJobRepository;
import lombok.RequiredArgsConstructor;

/**
 * Core {@link ReportJobHealthProvider} backed by {@code scheduled_report_jobs}:
 * counts enabled jobs and those whose last run failed, for the dashboard's
 * Report Jobs tile. Registered as the {@code @ConditionalOnMissingBean} default
 * in {@code CoreNoopSpiAutoConfiguration} — ee's implementation overrides it
 * until ee retires its scheduler.
 */
@RequiredArgsConstructor
public class CoreReportJobHealthProvider implements ReportJobHealthProvider {

    private final ScheduledReportJobRepository repository;

    @Override
    public ReportJobHealth health() {
        return new ReportJobHealth(
                repository.countByEnabledTrue(),
                repository.countByEnabledTrueAndLastRunStatus(ReportJobRunStatus.FAILED));
    }
}
