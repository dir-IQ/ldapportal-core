// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import java.time.OffsetDateTime;

/**
 * One entry in a scheduled-report job's {@code run_history} JSONB array: the
 * outcome of a single run. The newest entries are kept (the history is trimmed
 * to a bounded length on append) so operators can see a short timeline beyond
 * the {@code last_run_*} scalars.
 *
 * @param runAt   when the run completed
 * @param status  SUCCESS or FAILED
 * @param message human-readable detail (delivery summary or failure reason)
 */
public record ReportRunHistoryEntry(OffsetDateTime runAt, ReportJobRunStatus status, String message) {
}
