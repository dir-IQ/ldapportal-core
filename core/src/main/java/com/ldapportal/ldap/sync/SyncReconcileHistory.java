// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncReconcileRunDetail;
import com.ldapportal.dto.sync.SyncReconcileRunResponse;
import com.ldapportal.dto.sync.SyncRunFailureReason;
import com.ldapportal.dto.sync.SyncRunSettings;
import com.ldapportal.entity.SyncReconcileRun;
import com.ldapportal.entity.enums.SyncReconcileRunStatus;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.SyncReconcileRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Records each reconcile run of a sync set — what started it, where and when it
 * ran, the set's settings, whether the source scan completed, what it did to
 * each entry (counts, sample DNs, failures grouped by reason), and the drift the
 * verification after it found — so an operator can see what a past reconcile
 * actually did.
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
    /** Target DNs kept per outcome per run. */
    static final int SAMPLE_LIMIT = 25;
    /** Distinct failure reasons kept per run; the rest count under the last group. */
    static final int REASON_LIMIT = 20;
    static final String INTERRUPTED = "The run stopped without finishing (its server stopped or restarted).";
    static final String OTHER_REASONS = "other reasons";
    /** Separates a reason's kind from its entry-specific detail (the engine's {@code describe} format). */
    static final String SEPARATOR = " — ";

    /** Outcomes worth sampling: the ones that changed or need attention, plus adoptions. */
    private static final Set<SyncPreviewAction> SAMPLED = EnumSet.complementOf(EnumSet.of(SyncPreviewAction.UNCHANGED));

    private final SyncReconcileRunRepository runRepo;
    private final ObjectMapper objectMapper;
    private final Duration staleAfter;
    private final int keepPerSet;
    private final String instanceName;

    public SyncReconcileHistory(SyncReconcileRunRepository runRepo,
                                ObjectMapper objectMapper,
                                @Value("${ldapportal.sync.reconcile.run-stale-after-ms:900000}") long staleAfterMs,
                                @Value("${ldapportal.sync.reconcile.history-per-set:20}") int keepPerSet) {
        this.runRepo = runRepo;
        this.objectMapper = objectMapper;
        this.staleAfter = Duration.ofMillis(Math.max(1, staleAfterMs));
        this.keepPerSet = Math.max(1, keepPerSet);
        this.instanceName = resolveInstanceName();
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
        row.setInstanceName(instanceName);
        Run run = new Run(row);
        save(run);
        return run;
    }

    /** The plan is known: record its shape and the settings it ran with, before any entry is applied. */
    public void planned(Run run, int sourceCount, long managedBefore, boolean completeScan,
                        boolean guardTripped, String guardReason, SyncRunSettings settings) {
        run.row.setPlannedAt(OffsetDateTime.now());
        run.row.setSourceCount(sourceCount);
        run.row.setManagedBefore(managedBefore);
        run.row.setCompleteScan(completeScan);
        run.row.setGuardTripped(guardTripped);
        run.row.setGuardReason(guardReason);
        run.row.setSettings(toJson(settings));
        save(run);
    }

    /**
     * Count one entry's outcome; {@code null} means the engine skipped it (the
     * set or link was disabled during the run).
     */
    public void record(Run run, RecomputeOutcome outcome) {
        SyncReconcileRun r = run.row;
        r.setProcessed(r.getProcessed() + 1);
        if (outcome == null) {
            r.setSkipped(r.getSkipped() + 1);
        } else {
            switch (outcome.action()) {
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
            if (SAMPLED.contains(outcome.action()) && outcome.targetDn() != null) {
                List<String> dns = run.samples.computeIfAbsent(outcome.action().name(), k -> new ArrayList<>());
                if (dns.size() < SAMPLE_LIMIT) {
                    dns.add(outcome.targetDn());
                }
            }
            if (outcome.action() == SyncPreviewAction.ERROR) {
                run.countFailure(outcome.reason() != null ? outcome.reason() : "apply failed");
            }
        }
        beat(run);
    }

    /** Count an entry whose recompute threw (it retries on the next run). */
    public void fault(Run run, Exception ex) {
        run.row.setProcessed(run.row.getProcessed() + 1);
        run.row.setFaulted(run.row.getFaulted() + 1);
        String msg = ex.getMessage();
        run.countFailure("recompute error: " + ex.getClass().getSimpleName()
                + (msg == null || msg.isBlank() ? "" : SEPARATOR + msg));
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

    /**
     * Attach the content verification that ran right after a reconcile to that
     * run: the set's most recent finished run that has none yet.
     */
    public void recordDrift(UUID syncSetId, int missing, int orphan, int mismatch) {
        try {
            runRepo.findBySyncSetIdOrderByStartedAtDesc(syncSetId, PageRequest.of(0, 1)).stream()
                    .filter(r -> r.getFinishedAt() != null && r.getVerifiedAt() == null)
                    .findFirst()
                    .ifPresent(r -> {
                        r.setVerifiedAt(OffsetDateTime.now());
                        r.setVerifyMissing(missing);
                        r.setVerifyOrphan(orphan);
                        r.setVerifyMismatch(mismatch);
                        runRepo.save(r);
                    });
        } catch (Exception ex) {
            log.warn("Sync set {}: could not record post-reconcile drift: {}", syncSetId, ex.toString());
        }
    }

    /** A set's most recent runs, newest first. A running run with a stale heartbeat reads FAILED. */
    public List<SyncReconcileRunResponse> recent(UUID syncSetId, int limit) {
        OffsetDateTime staleBefore = OffsetDateTime.now().minus(staleAfter);
        return runRepo.findBySyncSetIdOrderByStartedAtDesc(syncSetId, PageRequest.of(0, limit)).stream()
                .map(r -> toResponse(r, staleBefore))
                .toList();
    }

    /** Everything recorded about one run of the set. */
    public SyncReconcileRunDetail detail(UUID syncSetId, UUID runId) {
        SyncReconcileRun r = runRepo.findById(runId)
                .filter(run -> run.getSyncSetId().equals(syncSetId))
                .orElseThrow(() -> new ResourceNotFoundException("Reconcile run not found"));
        SyncReconcileRunResponse summary = toResponse(r, OffsetDateTime.now().minus(staleAfter));
        SyncRunSettings settings = fromJson(r.getSettings(), new TypeReference<>() { }, null);
        List<SyncRunFailureReason> reasons = fromJson(r.getFailureReasons(), new TypeReference<>() { }, List.of());
        Map<String, List<String>> samples = fromJson(r.getSamples(), new TypeReference<>() { }, Map.of());
        return new SyncReconcileRunDetail(summary, settings, reasons, samples, SAMPLE_LIMIT);
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
                r.getGuardReason(), error, r.getInstanceName(), r.getPlannedAt(), r.getVerifiedAt(),
                r.getVerifyMissing(), r.getVerifyOrphan(), r.getVerifyMismatch());
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
            run.row.setSamples(run.samples.isEmpty() ? null : toJson(run.samples));
            run.row.setFailureReasons(run.failures.isEmpty() ? null : toJson(run.failureReasons()));
            runRepo.save(run.row);
        } catch (Exception ex) {
            log.warn("Sync set {}: could not record reconcile run {}: {}",
                    run.row.getSyncSetId(), run.row.getId(), ex.toString());
        }
    }

    private void prune(UUID syncSetId) {
        try {
            List<SyncReconcileRun> kept = runRepo.findBySyncSetIdOrderByStartedAtDesc(
                    syncSetId, PageRequest.of(0, keepPerSet));
            if (kept.size() == keepPerSet) {
                runRepo.deleteStartedBefore(syncSetId, kept.get(keepPerSet - 1).getStartedAt());
            }
        } catch (Exception ex) {
            log.warn("Sync set {}: could not prune reconcile history: {}", syncSetId, ex.toString());
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("Could not serialize reconcile run detail: {}", ex.toString());
            return null;
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            log.warn("Could not read reconcile run detail: {}", ex.toString());
            return fallback;
        }
    }

    /** The host name, as shown in a run's "ran on"; {@code HOSTNAME} first (the pod name under Kubernetes). */
    private static String resolveInstanceName() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ex) {
            return null;
        }
    }

    /** A run being recorded: its row, mutated in memory and flushed by heartbeat, plus its details. */
    public static final class Run {
        private final SyncReconcileRun row;
        /** Outcome name → sample target DNs. */
        private final Map<String, List<String>> samples = new LinkedHashMap<>();
        /** Reason kind → count and one full example. */
        private final Map<String, FailureGroup> failures = new LinkedHashMap<>();

        private Run(SyncReconcileRun row) {
            this.row = row;
        }

        /** The row as last tallied (package-private, for tests). */
        SyncReconcileRun row() {
            return row;
        }

        private void countFailure(String fullReason) {
            int cut = fullReason.indexOf(SEPARATOR);
            String kind = cut > 0 ? fullReason.substring(0, cut) : fullReason;
            if (!failures.containsKey(kind) && failures.size() >= REASON_LIMIT - 1) {
                kind = OTHER_REASONS;
            }
            failures.computeIfAbsent(kind, k -> new FailureGroup(fullReason)).count++;
        }

        private List<SyncRunFailureReason> failureReasons() {
            return failures.entrySet().stream()
                    .map(e -> new SyncRunFailureReason(e.getKey(), e.getValue().count, e.getValue().example))
                    .sorted(Comparator.comparingInt(SyncRunFailureReason::count).reversed())
                    .toList();
        }
    }

    private static final class FailureGroup {
        private final String example;
        private int count;

        private FailureGroup(String example) {
            this.example = example.length() > 500 ? example.substring(0, 497) + "…" : example;
        }
    }
}
