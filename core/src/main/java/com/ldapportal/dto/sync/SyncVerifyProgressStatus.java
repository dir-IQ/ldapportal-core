// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.enums.SyncPreviewRunStatus;
import com.ldapportal.entity.enums.SyncVerifyPhase;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A sync set's latest content verification: where it is while it runs, and how
 * it ended. A RUNNING one whose server stopped reads FAILED.
 *
 * @param processed entries read (SOURCE / TARGET) or compared (COMPARING) so far
 * @param total     entries to compare, once both sides are read ({@code 0} before)
 * @param currentDn the entry being compared, while COMPARING
 */
public record SyncVerifyProgressStatus(UUID runId, SyncPreviewRunStatus status, SyncVerifyPhase phase,
                                       int processed, int total, String currentDn, String error,
                                       OffsetDateTime startedAt, OffsetDateTime heartbeatAt,
                                       OffsetDateTime finishedAt) {
}
