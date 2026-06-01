// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.ReconciliationRun;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReconciliationRunStatus;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;
import com.ldapportal.ldap.replication.reconcile.ReconciliationDiffer.DiffResult;
import com.ldapportal.repository.ReconciliationRunRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Short, committed transactions for the reconciliation engine, on a
 * sibling bean so Spring's proxy applies (the scheduler/service run
 * non-transactionally, like the replication worker). Each method owns
 * its own {@code REQUIRES_NEW} tx.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationTxOps {

    private final ReconciliationRunRepository runRepo;
    private final ReplicationLinkRepository   linkRepo;

    private static final int ERROR_MAX = 4000;

    /** What {@link #tryStart} hands back so the engine can run without re-reading the link. */
    public record StartedRun(UUID runId, ReconcileMode mode, ReconcileDeleteAction deleteAction) {}

    /**
     * Claim a link for reconciliation by inserting a RUNNING run. Returns
     * empty when the link already has a live run (single-flight) — enforced
     * both by an explicit check and by the {@code uq_reconciliation_runs_one_active}
     * unique index (the catch handles a lost race).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<StartedRun> tryStart(UUID linkId, ReconciliationRunTrigger trigger) {
        ReplicationLink link = linkRepo.findById(linkId).orElse(null);
        if (link == null) return Optional.empty();
        if (runRepo.existsByLinkIdAndStatus(linkId, ReconciliationRunStatus.RUNNING)) {
            return Optional.empty();
        }
        ReconciliationRun run = new ReconciliationRun();
        run.setLink(link);
        run.setTrigger(trigger);
        run.setMode(link.getReconcileMode());
        run.setStatus(ReconciliationRunStatus.RUNNING);
        run.setClaimedAt(OffsetDateTime.now());
        run.setStartedAt(OffsetDateTime.now());
        try {
            run = runRepo.saveAndFlush(run);
        } catch (DataIntegrityViolationException race) {
            return Optional.empty();   // another tick/node claimed it first
        }
        return Optional.of(new StartedRun(run.getId(), link.getReconcileMode(), link.getReconcileDeleteAction()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeRun(UUID runId, DiffResult result, int appliedCount) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setStatus(ReconciliationRunStatus.COMPLETED);
            run.setSourceEntryCount(result.sourceCount());
            run.setTargetEntryCount(result.targetCount());
            run.setMissingCount(result.missingCount());
            run.setDriftCount(result.driftCount());
            run.setExtraCount(result.extraCount());
            run.setSuppressedCount(result.suppressedCount());
            run.setAppliedCount(appliedCount);
            run.setFinishedAt(OffsetDateTime.now());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRun(UUID runId, String error) {
        runRepo.findById(runId).ifPresent(run -> {
            run.setStatus(ReconciliationRunStatus.FAILED);
            run.setError(error == null ? null : error.substring(0, Math.min(error.length(), ERROR_MAX)));
            run.setFinishedAt(OffsetDateTime.now());
        });
    }

    /**
     * Stamp last-run and advance next-run by whole intervals until it is
     * strictly in the future (skip missed slots; never burst). No-op when
     * the link has since been disabled or its interval cleared.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advanceSchedule(UUID linkId, OffsetDateTime now) {
        linkRepo.findById(linkId).ifPresent(link -> {
            link.setReconcileLastRunAt(now);
            Integer secs = link.getReconcileIntervalSecs();
            if (!link.isReconcileEnabled() || secs == null || secs <= 0) return;
            OffsetDateTime next = link.getReconcileNextRunAt() != null ? link.getReconcileNextRunAt() : now;
            while (!next.isAfter(now)) next = next.plusSeconds(secs);
            link.setReconcileNextRunAt(next);
        });
    }

    /**
     * Flip RUNNING runs claimed before {@code threshold} to FAILED — they
     * were abandoned by a crashed worker — and reschedule their links.
     * @return number of runs reset
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resetStaleRuns(OffsetDateTime threshold) {
        List<ReconciliationRun> stale =
                runRepo.findByStatusAndClaimedAtBefore(ReconciliationRunStatus.RUNNING, threshold);
        OffsetDateTime now = OffsetDateTime.now();
        for (ReconciliationRun run : stale) {
            run.setStatus(ReconciliationRunStatus.FAILED);
            run.setError("Run abandoned (worker crash); reset by stale sweep");
            run.setFinishedAt(now);
            UUID linkId = run.getLink().getId();
            linkRepo.findById(linkId).ifPresent(link -> {
                link.setReconcileLastRunAt(now);
                Integer secs = link.getReconcileIntervalSecs();
                if (link.isReconcileEnabled() && secs != null && secs > 0) {
                    OffsetDateTime next = link.getReconcileNextRunAt() != null ? link.getReconcileNextRunAt() : now;
                    while (!next.isAfter(now)) next = next.plusSeconds(secs);
                    link.setReconcileNextRunAt(next);
                }
            });
        }
        return stale.size();
    }
}
