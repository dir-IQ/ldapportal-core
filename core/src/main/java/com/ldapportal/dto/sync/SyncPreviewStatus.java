// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.enums.SyncPreviewRunStatus;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * The latest per-entry reconcile preview of a sync set: progress while it
 * runs, and the summary once complete.
 *
 * @param runId           the run, or {@code null} when the set has never been previewed
 * @param status          {@code null} when never previewed; a run whose server
 *                        stopped mid-way reads FAILED
 * @param requestedBy     who started it
 * @param startedAt       when it started
 * @param finishedAt      when it finished, or {@code null}
 * @param processed       entries dry-run so far
 * @param total           entries to dry-run ({@code 0} until the source scan is planned)
 * @param currentDn       the entry being dry-run now, while RUNNING (else {@code null})
 * @param heartbeatAt     when the run last reported progress or liveness
 * @param asOf            the server's time when this status was read, so a client can
 *                        age {@code startedAt} and {@code heartbeatAt} without trusting its own clock
 * @param outdated        the set was edited or reconciled, its link repointed, or either
 *                        directory's connection settings edited, after this run started
 * @param error           why the run failed, when FAILED
 * @param sourceCount     entries enumerated in the source scope
 * @param managedCount    index rows the set managed when the run started
 * @param actionableCount entries that would change (what "changes only" lists)
 * @param unchangedCount  entries that wouldn't change (UNCHANGED or NO_CHANGE)
 * @param guardTripped    the delete guard would hold every delete for review
 * @param guardReason     why, when {@code guardTripped}
 * @param completeScan    false when source enumeration failed or stopped (no deletes would happen)
 * @param note            explanation of an incomplete scan
 * @param actionCounts    entries per outcome ({@code SyncPreviewAction} name → count),
 *                        counted from the stored entries once complete; empty before
 */
public record SyncPreviewStatus(
        UUID runId,
        SyncPreviewRunStatus status,
        String requestedBy,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        int processed,
        int total,
        String currentDn,
        OffsetDateTime heartbeatAt,
        OffsetDateTime asOf,
        boolean outdated,
        String error,
        int sourceCount,
        long managedCount,
        long actionableCount,
        long unchangedCount,
        boolean guardTripped,
        String guardReason,
        boolean completeScan,
        String note,
        Map<String, Long> actionCounts) {

    public static SyncPreviewStatus none() {
        return new SyncPreviewStatus(null, null, null, null, null, 0, 0, null, null, OffsetDateTime.now(), false, null,
                0, 0, 0, 0, false, null, false, null, Map.of());
    }
}
