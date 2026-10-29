// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.enums.SyncReconcileRunStatus;
import com.ldapportal.entity.enums.SyncReconcileTrigger;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One recorded reconcile run of a sync set: what started it and what it did.
 * The outcome counters mirror {@link SyncPreviewAction}, so a run compares
 * directly with the preview that came before it.
 *
 * @param id             the run
 * @param trigger        SCHEDULED (cadence or a changelog request) or MANUAL
 * @param requestedBy    the operator, for a MANUAL run
 * @param status         RUNNING, COMPLETE, or FAILED (aborted, or interrupted by a server stop)
 * @param startedAt      when it started
 * @param finishedAt     when it finished, or {@code null}
 * @param completeScan   false when source enumeration failed or stopped (no deletes were made)
 * @param sourceCount    source entries the run planned
 * @param managedBefore  index rows the set managed when the run started
 * @param managedAfter   index rows the set managed when it finished, or {@code null}
 * @param processed      entries recomputed (planned entries plus not-seen rows)
 * @param added          target entries created
 * @param modified       target entries whose attributes changed
 * @param moved          target entries renamed/moved
 * @param deleted        target entries deleted
 * @param heldForReview  entries quarantined for an operator decision
 * @param noChange       entries applied without a target write (the target already matched)
 * @param forgotten      stale index rows dropped (another identity owns the target entry)
 * @param unchanged      entries already applied with the same content
 * @param failed         entries whose target write failed (rows now FAILED)
 * @param faulted        entries whose recompute threw (retried next run)
 * @param skipped        entries not processed because the set or link was disabled mid-run
 * @param guardTripped   the delete guard held every delete for review
 * @param guardReason    why, when {@code guardTripped}
 * @param error          why the run failed, when FAILED
 * @param instanceName   the app instance (host) that ran it
 * @param plannedAt      when the source scan and plan finished (scan = plannedAt - startedAt)
 * @param verifiedAt     when the content verification after the run ran, or {@code null}
 * @param verifyMissing  members missing on the target after the run (with {@code verifiedAt})
 * @param verifyOrphan   target entries with no source member after the run
 * @param verifyMismatch entries whose content still differed after the run
 * @param allDnsRecorded whether every changed or flagged entry's DN was kept
 *                       (downloadable), not just the samples
 * @param notInSet       in-scope source entries the set doesn't manage (the
 *                       applicability filter or placement excludes them)
 * @param phase          where the run is while RUNNING, else {@code null}
 * @param currentDn      the entry being recomputed while RUNNING, else {@code null}
 * @param heartbeatAt    when the run last reported progress or that it's alive
 */
public record SyncReconcileRunResponse(
        UUID id,
        SyncReconcileTrigger trigger,
        String requestedBy,
        SyncReconcileRunStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        boolean completeScan,
        int sourceCount,
        long managedBefore,
        Long managedAfter,
        int processed,
        int added,
        int modified,
        int moved,
        int deleted,
        int heldForReview,
        int noChange,
        int forgotten,
        int unchanged,
        int failed,
        int faulted,
        int skipped,
        boolean guardTripped,
        String guardReason,
        String error,
        String instanceName,
        OffsetDateTime plannedAt,
        OffsetDateTime verifiedAt,
        Integer verifyMissing,
        Integer verifyOrphan,
        Integer verifyMismatch,
        boolean allDnsRecorded,
        int notInSet,
        com.ldapportal.entity.enums.SyncReconcilePhase phase,
        String currentDn,
        OffsetDateTime heartbeatAt) {
}
