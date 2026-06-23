// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import java.time.OffsetDateTime;

/**
 * One entry in a scheduled-report job's {@code run_history} JSONB array: the
 * outcome of a single run. The newest entries are kept (the history is trimmed
 * to a bounded length on append) so operators can see a short timeline beyond
 * the {@code last_run_*} scalars.
 *
 * <p>The {@code startedAt} and {@code trigger} fields were added after the
 * original three-field shape. Because the column is schemaless JSONB, older
 * entries simply deserialize with those fields {@code null} — no migration is
 * needed — and the UI renders the duration / trigger as unknown for them.</p>
 *
 * @param startedAt when the run began (null for pre-enrichment entries)
 * @param runAt     when the run completed (also the {@code last_run_at} instant)
 * @param status    SUCCESS, FAILED, or SKIPPED
 * @param message   human-readable detail (delivery summary or failure reason)
 * @param trigger   what initiated the run (null for pre-enrichment entries)
 */
public record ReportRunHistoryEntry(
        OffsetDateTime startedAt,
        OffsetDateTime runAt,
        ReportJobRunStatus status,
        String message,
        ReportRunTrigger trigger) {
}
