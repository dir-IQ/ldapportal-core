// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncReconcileRunResponse;
import com.ldapportal.entity.SyncReconcileRun;
import com.ldapportal.entity.enums.SyncReconcileRunStatus;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.repository.SyncReconcileRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Records each reconcile run of a sync set — what started it, whether the
 * source scan completed, and what it did to each entry — so an operator can see
 * what the last reconcile actually did.
 *
 * <p>Bookkeeping only: a failure to write the history is logged and swallowed,
 * never allowed to fail the reconcile it describes. Counts are flushed with a
 * heartbeat at most every {@link #HEARTBEAT_INTERVAL} while the run progresses,
 * and once at the end.
 */
@Component
@Slf4j
public class SyncReconcileHistory {

    /** How often a running run flushes its counts and heartbeat. */
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    /** Runs kept per set; older ones are pruned when a run finishes. */
    static final int KEEP_PER_SET = 20;
    static final String INTERRUPTED = "The run stopped without finishing (its server stopped or restarted).";

    private final SyncReconcileRunRepository runRepo;
    private final Duration staleAfter;

    public SyncReconcileHistory(SyncReconcileRunRepository runRepo,
                                @Value("${ldapportal.sync.reconcile.run-stale-after-ms:900000}") long staleAfterMs) {
        this.runRepo = runRepo;
        this.staleAfter = Duration.ofMillis(Math.max(1, staleAfterMs));
    }

    /** Start recording a run. Never {@code null}: if the row can't be written, the run is still tallied. */
    public Run start(UUID syncSetId, SyncReconcileTrigger trigger, String requestedBy) {
        OffsetDateTime now = OffsetDateTime.now();
        SyncReconcileRun row = new SyncReconcileRun();
        row.setId(UUID.randomUUID());
        row.setSyncSetId(syncSetId);
        row.setTrigger(trigger);
        row.setRequestedBy(requestedBy);
        row.setStatus(SyncReconcileRunStatus.RUNNING);
        row.setStartedAt(now);
        row.setHeartbeatAt(now);
        Run run = new Run(row);
        save(run);
        return run;
    }

    /** The plan is known: record its shape before any entry is applied. */
    public void planned(Run run, int sourceCount, long managedBefore, boolean completeScan,
                        boolean guardTripped, String guardReason) {
        run.row.setSourceCount(sourceCount);
        run.row.setManagedBefore(managedBefore);
        run.row.setCompleteScan(completeScan);
        run.row.setGuardTripped(guardTripped);
        run.row.setGuardReason(guardReason);
        save(run);
    }

    /**
     * Count one entry's outcome; {@code null} means the engine skipped it (the
     * set or link was disabled during the run).
     */
    public void record(Run run, SyncPreviewAction action) {
        SyncReconcileRun r = run.row;
        r.setProcessed(r.getProcessed() + 1);
        if (action == null) {
            r.setSkipped(r.getSkipped() + 1);
        } else {
            switch (action) {
                case ADD -> r.setAdded(r.getAdded() + 1);
                case MODIFY -> r.setModified(r.getModified() + 1);
                case MOVE -> r.setMoved(r.getMoved() + 1);
                case DELETE -> r.setDeleted(r.getDeleted() + 1);
                case REVIEW -> r.setHeldForReview(r.getHeldForReview() + 1);
                case NO_CHANGE -> r.setNoChange(r.getNoChange() + 1);
                case FORGET -> r.setForgotten(r.getForgotten() + 1);
                case UNCHANGED -> r.setUnchanged(r.getUnchanged() + 1);
                case ERROR -> r.setFailed(r.getFailed() + 1);
            }
        }
        beat(run);
    }

    /** Count an entry whose recompute threw (it retries on the next run). */
    public void fault(Run run) {
        run.row.setProcessed(run.row.getProcessed() + 1);
        run.row.setFaulted(run.row.getFaulted() + 1);
        beat(run);
    }

    /** The run reached its end. */
    public void complete(Run run, long managedAfter) {
        OffsetDateTime now = OffsetDateTime.now();
        run.row.setStatus(SyncReconcileRunStatus.COMPLETE);
        run.row.setFinishedAt(now);
        run.row.setHeartbeatAt(now);
        run.row.setManagedAfter(managedAfter);
        save(run);
        prune(run.row.getSyncSetId());
    }

    /** The run aborted on an unexpected error. */
    public void fail(Run run, Exception ex) {
        OffsetDateTime now = OffsetDateTime.now();
        run.row.setStatus(SyncReconcileRunStatus.FAILED);
        run.row.setFinishedAt(now);
        run.row.setHeartbeatAt(now);
        String msg = ex.getMessage();
        run.row.setError(msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : msg);
        save(run);
        prune(run.row.getSyncSetId());
    }

    /** A set's most recent runs, newest first. A running run with a stale heartbeat reads FAILED. */
    public List<SyncReconcileRunResponse> recent(UUID syncSetId, int limit) {
        OffsetDateTime staleBefore = OffsetDateTime.now().minus(staleAfter);
        return runRepo.findBySyncSetIdOrderByStartedAtDesc(syncSetId, PageRequest.of(0, limit)).stream()
                .map(r -> toResponse(r, staleBefore))
                .toList();
    }

    static SyncReconcileRunResponse toResponse(SyncReconcileRun r, OffsetDateTime staleBefore) {
        SyncReconcileRunStatus status = r.getStatus();
        String error = r.getError();
        if (status == SyncReconcileRunStatus.RUNNING && r.getHeartbeatAt().isBefore(staleBefore)) {
            status = SyncReconcileRunStatus.FAILED;
            error = INTERRUPTED;
        }
        return new SyncReconcileRunResponse(r.getId(), r.getTrigger(), r.getRequestedBy(), status,
                r.getStartedAt(), r.getFinishedAt(), r.isCompleteScan(), r.getSourceCount(),
                r.getManagedBefore(), r.getManagedAfter(), r.getProcessed(), r.getAdded(), r.getModified(),
                r.getMoved(), r.getDeleted(), r.getHeldForReview(), r.getNoChange(), r.getForgotten(),
                r.getUnchanged(), r.getFailed(), r.getFaulted(), r.getSkipped(), r.isGuardTripped(),
                r.getGuardReason(), error);
    }

    private void beat(Run run) {
        OffsetDateTime now = OffsetDateTime.now();
        if (!run.row.getHeartbeatAt().plus(HEARTBEAT_INTERVAL).isAfter(now)) {
            run.row.setHeartbeatAt(now);
            save(run);
        }
    }

    private void save(Run run) {
        try {
            runRepo.save(run.row);
        } catch (Exception ex) {
            log.warn("Sync set {}: could not record reconcile run {}: {}",
                    run.row.getSyncSetId(), run.row.getId(), ex.toString());
        }
    }

    private void prune(UUID syncSetId) {
        try {
            List<SyncReconcileRun> kept = runRepo.findBySyncSetIdOrderByStartedAtDesc(
                    syncSetId, PageRequest.of(0, KEEP_PER_SET));
            if (kept.size() == KEEP_PER_SET) {
                runRepo.deleteStartedBefore(syncSetId, kept.get(KEEP_PER_SET - 1).getStartedAt());
            }
        } catch (Exception ex) {
            log.warn("Sync set {}: could not prune reconcile history: {}", syncSetId, ex.toString());
        }
    }

    /** A run being recorded: its row, mutated in memory and flushed by heartbeat. */
    public static final class Run {
        private final SyncReconcileRun row;

        private Run(SyncReconcileRun row) {
            this.row = row;
        }

        /** The row as last tallied (package-private, for tests). */
        SyncReconcileRun row() {
            return row;
        }
    }
}
