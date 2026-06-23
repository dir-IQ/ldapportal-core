// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

/**
 * What initiated a scheduled-report job run, persisted on each
 * {@link ReportRunHistoryEntry} so the run log distinguishes the regular
 * cron-driven runs from operator-triggered "Run now" executions.
 *
 * <p>Entries written before this field existed deserialize with a {@code null}
 * trigger; the UI shows those as unknown.</p>
 */
public enum ReportRunTrigger {
    /** The poll loop ran the job because its cron schedule was due. */
    SCHEDULED,
    /** An operator triggered the run off-schedule via the "Run now" action. */
    MANUAL
}
