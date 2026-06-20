// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

/**
 * Outcome of a scheduled-report job run, persisted as the enum name in
 * {@code scheduled_report_jobs.last_run_status} and in each
 * {@link ReportRunHistoryEntry}. The dashboard report-job health tile counts
 * {@link #FAILED} jobs.
 */
public enum ReportJobRunStatus {
    SUCCESS,
    FAILED
}
