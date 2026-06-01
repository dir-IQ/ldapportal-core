// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;
import com.ldapportal.ldap.replication.ReplicationLinkSnapshot;
import com.ldapportal.ldap.replication.ReplicationReadOps;
import com.ldapportal.ldap.replication.reconcile.ReconciliationDiffer.DiffResult;
import com.ldapportal.dto.replication.ReconciliationRunResponse;
import com.ldapportal.ldap.replication.reconcile.ReconciliationTxOps.StartedRun;
import com.ldapportal.repository.ReconciliationRunRepository;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.service.AuditService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Orchestrates a reconciliation run: read both subtrees, diff, suppress
 * findings shadowed by the live queue, and — for the configured mode /
 * delete-action — enqueue corrective {@code replication_events} that the
 * existing worker delivers. The run row carries the discrepancy summary;
 * per-finding persistence + the review UI arrive in R-P2.
 *
 * <p>Runs non-transactionally (like {@code ReplicationWorker}); all DB
 * mutations go through {@link ReconciliationTxOps}. The heavy compare
 * runs off the caller's thread on a small bounded pool.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    private final ReplicationReadOps         replicationReadOps;
    private final ChecksumReconciler         checksumReconciler;
    private final ReconciliationFindingTxOps findingTxOps;
    private final ReplicationEventRepository eventRepo;
    private final ReconciliationRunRepository runRepo;
    private final ReconciliationTxOps        txOps;
    private final AuditService               auditService;

    @Value("${ldapportal.reconciliation.max-findings-per-run:5000}")
    private int maxFindingsPerRun;
    @Value("${ldapportal.reconciliation.pool-size:2}")
    private int poolSize;

    private ExecutorService executor;

    @PostConstruct
    void initExecutor() {
        executor = Executors.newFixedThreadPool(Math.max(1, poolSize), r -> {
            Thread t = new Thread(r, "reconciliation");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdownExecutor() {
        if (executor != null) executor.shutdownNow();
    }

    /**
     * Claim the link and dispatch a run off-thread. Returns the new run id,
     * or empty when the link already has a live run (single-flight).
     */
    public Optional<UUID> trigger(UUID linkId, ReconciliationRunTrigger trigger, AuthPrincipal principal) {
        Optional<StartedRun> started = txOps.tryStart(linkId, trigger);
        if (started.isEmpty()) return Optional.empty();
        StartedRun sr = started.get();
        recordRunStarted(linkId, trigger, principal);
        executor.submit(() -> execute(sr, linkId, trigger, principal));
        return Optional.of(sr.runId());
    }

    /** Run history for a link, newest first. */
    @Transactional(readOnly = true)
    public Page<ReconciliationRunResponse> listRuns(UUID linkId, Pageable pageable) {
        return runRepo.findByLinkIdOrderByStartedAtDesc(linkId, pageable)
                .map(ReconciliationRunResponse::from);
    }

    /** The heavy compare. Always finalizes the run and advances the schedule. */
    void execute(StartedRun sr, UUID linkId, ReconciliationRunTrigger trigger, AuthPrincipal principal) {
        OffsetDateTime now = OffsetDateTime.now();
        try {
            ReplicationLinkSnapshot link = replicationReadOps.snapshotById(linkId).orElse(null);
            if (link == null) {
                txOps.failRun(sr.runId(), "Replication link no longer exists");
                recordRunFailed(linkId, trigger, principal, "Replication link no longer exists");
                return;
            }
            String sourceBase = link.sourceBaseDn() != null
                    ? link.sourceBaseDn() : link.sourceDirectory().getBaseDn();
            String targetBase = link.targetBaseDn() != null
                    ? link.targetBaseDn() : link.targetDirectory().getBaseDn();

            Set<String> undelivered = eventRepo.findUndeliveredTargetDns(linkId).stream()
                    .map(ReconciliationDiffer::normDn)
                    .collect(Collectors.toSet());

            // Paged + checksum two-pass read (R-PP1): bounds peak memory and
            // reads large subtrees in pages instead of failing on a size-limit.
            DiffResult diff = checksumReconciler.reconcile(
                    link, sourceBase, targetBase, undelivered, sr.deleteAction());

            // Safety cap: a base-DN typo can make every entry look missing/extra.
            // Abort rather than enqueue a mass mutation.
            if (diff.findings().size() > maxFindingsPerRun) {
                String msg = "Aborted: " + diff.findings().size()
                        + " findings exceed the safety cap of " + maxFindingsPerRun
                        + " — check the link's base-DN configuration";
                txOps.failRun(sr.runId(), msg);
                recordRunFailed(linkId, trigger, principal, msg);
                return;
            }

            // Persist every surviving finding; auto-apply (enqueue corrective
            // events) those the mode / delete-action call for. Review-mode
            // findings stay PROPOSED for the operator (R-P2).
            int applied = findingTxOps.persistFindings(
                    sr.runId(), linkId, diff.findings(), sr.mode(), sr.deleteAction());
            txOps.completeRun(sr.runId(), diff, applied);
            recordRunCompleted(linkId, trigger, principal, diff, applied, "COMPLETED");
            log.info("Reconciliation run {} for link {} completed: missing={} drift={} extra={} suppressed={} applied={}",
                    sr.runId(), linkId, diff.missingCount(), diff.driftCount(),
                    diff.extraCount(), diff.suppressedCount(), applied);
        } catch (RuntimeException ex) {
            log.error("Reconciliation run {} for link {} failed: {}", sr.runId(), linkId, ex.toString());
            txOps.failRun(sr.runId(), ex.toString());
            recordRunFailed(linkId, trigger, principal, ex.toString());
        } finally {
            txOps.advanceSchedule(linkId, now);
        }
    }

    // ── audit ────────────────────────────────────────────────────────────────

    private void recordRunStarted(UUID linkId, ReconciliationRunTrigger trigger, AuthPrincipal principal) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("linkId", linkId.toString());
        detail.put("trigger", trigger.name());
        emit(AuditAction.RECONCILIATION_RUN_STARTED, detail, principal);
    }

    private void recordRunCompleted(UUID linkId, ReconciliationRunTrigger trigger, AuthPrincipal principal,
                                    DiffResult diff, int applied, String outcome) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("linkId", linkId.toString());
        detail.put("trigger", trigger.name());
        detail.put("outcome", outcome);
        detail.put("sourceEntryCount", diff.sourceCount());
        detail.put("targetEntryCount", diff.targetCount());
        detail.put("missing", diff.missingCount());
        detail.put("drift", diff.driftCount());
        detail.put("extra", diff.extraCount());
        detail.put("suppressed", diff.suppressedCount());
        detail.put("autoApplied", applied);
        emit(AuditAction.RECONCILIATION_RUN_COMPLETED, detail, principal);
    }

    private void recordRunFailed(UUID linkId, ReconciliationRunTrigger trigger,
                                 AuthPrincipal principal, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("linkId", linkId.toString());
        detail.put("trigger", trigger.name());
        detail.put("reason", reason);
        emit(AuditAction.RECONCILIATION_RUN_FAILED, detail, principal);
    }

    private void emit(AuditAction action, Map<String, Object> detail, AuthPrincipal principal) {
        if (principal != null) {
            auditService.recordSystemEvent(principal, action, detail);
        } else {
            auditService.recordSystemEventNoActor(action, detail);
        }
    }
}
