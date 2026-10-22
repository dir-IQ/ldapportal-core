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
import com.ldapportal.entity.SyncSet;
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
import java.util.function.Consumer;

/**
 * Records each reconcile run of a sync set — what started it, where and when it
 * ran, the set's settings, whether the source scan completed, what it did to
 * each entry (counts, sample DNs, failures grouped by reason), and the drift the
 * verification after it found — so an operator can see what a past reconcile
 * actually did.
 *
 * <p>Between reconciles, the recompute worker's passes are recorded the same
 * way as {@link SyncReconcileTrigger#INCREMENTAL INCREMENTAL} runs, kept only
 * when a pass did something (see {@link #startIncremental}) and pruned
 * separately, so a busy changelog can't push the reconcile runs out.
 *
 * <p>Every target write a run makes is audited ({@link SyncWriteAuditor}). When
 * the set records all DNs, every entry the run changed or flagged is kept too,
 * beyond the {@link #SAMPLE_LIMIT} samples per outcome ({@link SyncRunEntryStore}).
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

    /** Outcomes that make an incremental pass worth keeping: writes, and entries that need attention. */
    private static final Set<SyncPreviewAction> NOTEWORTHY = EnumSet.of(SyncPreviewAction.ADD,
            SyncPreviewAction.MODIFY, SyncPreviewAction.MOVE, SyncPreviewAction.DELETE,
            SyncPreviewAction.REVIEW, SyncPreviewAction.FORGET, SyncPreviewAction.ERROR);
    /** Outcome recorded in the entry list for a recompute that threw. */
    static final String FAULT = "FAULT";

    private final SyncReconcileRunRepository runRepo;
    private final SyncRunEntryStore entryStore;
    private final SyncWriteAuditor auditor;
    private final ObjectMapper objectMapper;
    private final Duration staleAfter;
    private final int keepPerSet;
    private final int keepIncrementalPerSet;
    private final String instanceName;

    public SyncReconcileHistory(SyncReconcileRunRepository runRepo,
                                SyncRunEntryStore entryStore,
                                SyncWriteAuditor auditor,
                                ObjectMapper objectMapper,
                                @Value("${ldapportal.sync.reconcile.run-stale-after-ms:900000}") long staleAfterMs,
                                @Value("${ldapportal.sync.reconcile.history-per-set:20}") int keepPerSet,
                                @Value("${ldapportal.sync.reconcile.incremental-history-per-set:50}")
                                int keepIncrementalPerSet) {
        this.runRepo = runRepo;
        this.entryStore = entryStore;
        this.auditor = auditor;
        this.objectMapper = objectMapper;
        this.staleAfter = Duration.ofMillis(Math.max(1, staleAfterMs));
        this.keepPerSet = Math.max(1, keepPerSet);
        this.keepIncrementalPerSet = Math.max(1, keepIncrementalPerSet);
        this.instanceName = resolveInstanceName();
    }

    /**
     * Start recording a reconcile of {@code set}, whose writes land in
     * {@code targetDirectoryId}. Never {@code null}: if the row can't be
     * written, the run is still tallied.
     */
    public Run start(SyncSet set, UUID targetDirectoryId, SyncReconcileTrigger trigger, String requestedBy) {
        Run run = newRun(set, targetDirectoryId, trigger, requestedBy, false);
        save(run);
        return run;
    }

    /**
     * Start recording one recompute-worker pass over {@code set}'s queued
     * changes. Held in memory until {@link #finishIncremental}, which keeps it
     * only if the pass did something noteworthy; its writes are audited as
     * they happen either way.
     */
    public Run startIncremental(SyncSet set, UUID targetDirectoryId) {
        Run run = newRun(set, targetDirectoryId, SyncReconcileTrigger.INCREMENTAL, null, true);
        run.row.setCompleteScan(false);
        return run;
    }

    /**
     * End an incremental pass: saved as a completed run when it wrote to the
     * target or flagged an entry, else dropped.
     *
     * @return whether the pass was recorded
     */
    public boolean finishIncremental(Run run, long managedAfter) {
        if (!run.noteworthy) {
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        run.deferred = false;
        run.row.setSourceCount(run.row.getProcessed());
        run.row.setManagedBefore(managedAfter);
        run.row.setManagedAfter(managedAfter);
        run.row.setPlannedAt(run.row.getStartedAt());
        run.row.setStatus(SyncReconcileRunStatus.COMPLETE);
        run.row.setFinishedAt(now);
        run.row.setHeartbeatAt(now);
        save(run);
        prune(run.row.getSyncSetId(), false);
        return true;
    }

    private Run newRun(SyncSet set, UUID targetDirectoryId, SyncReconcileTrigger trigger, String requestedBy,
                       boolean deferred) {
        OffsetDateTime now = OffsetDateTime.now();
        SyncReconcileRun row = new SyncReconcileRun();
        row.setId(UUID.randomUUID());
        row.setSyncSetId(set.getId());
        row.setTrigger(trigger);
        row.setRequestedBy(requestedBy);
        row.setStatus(SyncReconcileRunStatus.RUNNING);
        row.setStartedAt(now);
        row.setHeartbeatAt(now);
        row.setInstanceName(instanceName);
        row.setAllDnsRecorded(set.isRecordAllDns());
        return new Run(row, targetDirectoryId, deferred);
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
                run.keep(outcome.action().name(), outcome.targetDn(), outcome.reason());
            }
            if (NOTEWORTHY.contains(outcome.action())) {
                run.noteworthy = true;
            }
            audit(run, outcome);
            if (outcome.action() == SyncPreviewAction.ERROR) {
                run.countFailure(outcome.reason() != null ? outcome.reason() : "apply failed");
            }
        }
        beat(run);
    }

    /**
     * Count an entry whose recompute threw (it retries on the next run).
     * {@code key} — the source DN or identity being recomputed — stands in for
     * the target DN in the full entry list, which the fault never reached.
     */
    public void fault(Run run, Exception ex, String key) {
        run.row.setProcessed(run.row.getProcessed() + 1);
        run.row.setFaulted(run.row.getFaulted() + 1);
        String msg = ex.getMessage();
        String reason = "recompute error: " + ex.getClass().getSimpleName()
                + (msg == null || msg.isBlank() ? "" : SEPARATOR + msg);
        run.countFailure(reason);
        run.noteworthy = true;
        if (key != null) {
            run.keep(FAULT, key, reason);
        }
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
        prune(run.row.getSyncSetId(), true);
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
        prune(run.row.getSyncSetId(), true);
    }

    /**
     * Attach the content verification that ran right after a reconcile to that
     * run: the set's most recent finished run that has none yet.
     */
    public void recordDrift(UUID syncSetId, int missing, int orphan, int mismatch) {
        try {
            runRepo.findBySyncSetIdAndTriggerInOrderByStartedAtDesc(
                            syncSetId, SyncReconcileTrigger.RECONCILES, PageRequest.of(0, 1)).stream()
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

    /**
     * A set's most recent runs, newest first: reconciles, incremental passes,
     * or both ({@code kind} {@code null}). A running run with a stale
     * heartbeat reads FAILED.
     */
    public List<SyncReconcileRunResponse> recent(UUID syncSetId, int limit, RunKind kind) {
        OffsetDateTime staleBefore = OffsetDateTime.now().minus(staleAfter);
        PageRequest page = PageRequest.of(0, limit);
        List<SyncReconcileRun> runs = kind == null
                ? runRepo.findBySyncSetIdOrderByStartedAtDesc(syncSetId, page)
                : runRepo.findBySyncSetIdAndTriggerInOrderByStartedAtDesc(syncSetId, kind.triggers, page);
        return runs.stream().map(r -> toResponse(r, staleBefore)).toList();
    }

    /** Which runs {@link #recent} lists. */
    public enum RunKind {
        RECONCILE(SyncReconcileTrigger.RECONCILES),
        INCREMENTAL(EnumSet.of(SyncReconcileTrigger.INCREMENTAL));

        private final Set<SyncReconcileTrigger> triggers;

        RunKind(Set<SyncReconcileTrigger> triggers) {
            this.triggers = triggers;
        }
    }

    /**
     * Hand each entry a run changed or flagged to {@code sink}, in processing
     * order: every one, when its set recorded all DNs.
     *
     * @throws ResourceNotFoundException when the run isn't one of the set's
     * @throws IllegalArgumentException when the run kept only samples
     */
    public void entries(UUID syncSetId, UUID runId, Consumer<SyncRunEntryStore.Entry> sink) {
        requireAllDnsRecorded(syncSetId, runId);
        entryStore.forEach(runId, sink);
    }

    /**
     * Check that {@code runId} is one of the set's runs and kept every DN, so
     * a download can fail with a proper status before it starts streaming.
     *
     * @throws ResourceNotFoundException when the run isn't one of the set's
     * @throws IllegalArgumentException when the run kept only samples
     */
    public void requireAllDnsRecorded(UUID syncSetId, UUID runId) {
        SyncReconcileRun r = runRepo.findById(runId)
                .filter(run -> run.getSyncSetId().equals(syncSetId))
                .orElseThrow(() -> new ResourceNotFoundException("Reconcile run not found"));
        if (!r.isAllDnsRecorded()) {
            throw new IllegalArgumentException("This run kept only sample DNs: its sync set wasn't recording "
                    + "every DN when it ran.");
        }
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
                r.getVerifyMissing(), r.getVerifyOrphan(), r.getVerifyMismatch(), r.isAllDnsRecorded());
    }

    private void beat(Run run) {
        OffsetDateTime now = OffsetDateTime.now();
        if (!run.row.getHeartbeatAt().plus(HEARTBEAT_INTERVAL).isAfter(now)) {
            run.row.setHeartbeatAt(now);
            save(run);
        }
    }

    private void audit(Run run, RecomputeOutcome outcome) {
        try {
            auditor.audit(run.targetDirectoryId, run.row.getSyncSetId(), run.row.getId(), run.row.getTrigger(),
                    outcome);
        } catch (Exception ex) {
            log.warn("Sync set {}: could not audit the write to {}: {}",
                    run.row.getSyncSetId(), outcome.targetDn(), ex.toString());
        }
    }

    private void save(Run run) {
        if (run.deferred) {
            return;
        }
        try {
            run.row.setSamples(run.samples.isEmpty() ? null : toJson(run.samples));
            run.row.setFailureReasons(run.failures.isEmpty() ? null : toJson(run.failureReasons()));
            runRepo.save(run.row);
        } catch (Exception ex) {
            log.warn("Sync set {}: could not record reconcile run {}: {}",
                    run.row.getSyncSetId(), run.row.getId(), ex.toString());
            return;
        }
        flushEntries(run);
    }

    /** Write the entries kept since the last flush; the run's row must be saved first. */
    private void flushEntries(Run run) {
        if (run.pending.isEmpty()) {
            return;
        }
        List<SyncRunEntryStore.Entry> batch = List.copyOf(run.pending);
        run.pending.clear();
        try {
            entryStore.append(run.row.getId(), batch);
        } catch (Exception ex) {
            // Bookkeeping only, like the rest of the history: the run carries on.
            log.warn("Sync set {}: could not record {} entries of run {}: {}",
                    run.row.getSyncSetId(), batch.size(), run.row.getId(), ex.toString());
        }
    }

    /** Keep the newest runs of one kind: reconciles and incremental passes are pruned separately. */
    private void prune(UUID syncSetId, boolean reconciles) {
        Set<SyncReconcileTrigger> triggers = reconciles
                ? SyncReconcileTrigger.RECONCILES : EnumSet.of(SyncReconcileTrigger.INCREMENTAL);
        int keep = reconciles ? keepPerSet : keepIncrementalPerSet;
        try {
            List<SyncReconcileRun> kept = runRepo.findBySyncSetIdAndTriggerInOrderByStartedAtDesc(
                    syncSetId, triggers, PageRequest.of(0, keep));
            if (kept.size() == keep) {
                runRepo.deleteStartedBefore(syncSetId, triggers, kept.get(keep - 1).getStartedAt());
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
        /** The directory the run writes to, for the audit records. */
        private final UUID targetDirectoryId;
        /** Outcome name → sample target DNs. */
        private final Map<String, List<String>> samples = new LinkedHashMap<>();
        /** Reason kind → count and one full example. */
        private final Map<String, FailureGroup> failures = new LinkedHashMap<>();
        /** Entries kept since the last flush (only when the run records all DNs). */
        private final List<SyncRunEntryStore.Entry> pending = new ArrayList<>();
        private int nextSeq;
        /** An incremental pass not yet saved: nothing is written until it proves noteworthy. */
        private boolean deferred;
        /** It wrote to the target or flagged an entry. */
        private boolean noteworthy;

        private Run(SyncReconcileRun row, UUID targetDirectoryId, boolean deferred) {
            this.row = row;
            this.targetDirectoryId = targetDirectoryId;
            this.deferred = deferred;
        }

        /** The run's id, which is also the correlation id of its audit records. */
        public UUID id() {
            return row.getId();
        }

        /** The row as last tallied (package-private, for tests). */
        SyncReconcileRun row() {
            return row;
        }

        private void keep(String outcome, String dn, String reason) {
            if (row.isAllDnsRecorded()) {
                pending.add(new SyncRunEntryStore.Entry(nextSeq++, outcome, dn, reason));
            }
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
