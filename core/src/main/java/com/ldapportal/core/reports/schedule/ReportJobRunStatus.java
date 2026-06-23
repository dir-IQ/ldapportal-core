// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

/**
 * Outcome of a scheduled-report job run, persisted as the enum name in
 * {@code scheduled_report_jobs.last_run_status} and in each
 * {@link ReportRunHistoryEntry}. The dashboard report-job health tile counts
 * {@link #FAILED} jobs.
 */
public enum ReportJobRunStatus {
    /** Report generated and delivery was accepted (e.g. SMTP 2xx, S3 upload OK). */
    SUCCESS,
    /** The run failed — generation error, a gated capability, or a rejected delivery. */
    FAILED,
    /**
     * Report generated but not delivered because no delivery channel was
     * configured (e.g. SMTP not set up). Distinct from {@link #SUCCESS} so a
     * misconfigured mailer never masquerades as a delivered report; not counted
     * as a failure on the dashboard health tile.
     */
    SKIPPED
}
