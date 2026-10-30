// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.sync.SyncNotInSetGroup;
import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncReconcileRunDetail;
import com.ldapportal.dto.sync.SyncReconcileRunResponse;
import com.ldapportal.dto.sync.SyncRunFailureReason;
import com.ldapportal.dto.sync.SyncRunSettings;
import com.ldapportal.entity.SyncReconcileRun;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.SyncLoggingLevel;
import com.ldapportal.entity.enums.SyncNotInSetReason;
import com.ldapportal.entity.enums.SyncReconcilePhase;
import com.ldapportal.entity.enums.SyncReconcileRunStatus;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.SyncReconcileRunRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Records each reconcile run of a sync set — what started it, where and when it
 * ran, the set's settings, whether the source scan completed, what it did to
 * each entry (counts, failures grouped by reason and, at the set's detailed
 * logging level, the entries' DNs), and the drift the
 * verification after it found — so an operator can see what a past reconcile
 * actually did.
 *
 * <p>Between reconciles, the recompute worker's passes are recorded the same
 * way as {@link SyncReconcileTrigger#INCREMENTAL INCREMENTAL} runs, kept only
 * when a pass did something (see {@link #startIncremental}) and pruned
 * separately, so a busy changelog can't push the reconcile runs out.
 *
 * <p>Every target write a run makes is audited ({@link SyncWriteAuditor}). At
 * the {@link SyncLoggingLevel#DETAILED detailed} logging level, every entry the
 * run changed or flagged is kept too: up to {@link #SAMPLE_LIMIT} per outcome on
 * the run, and all of them in {@link SyncRunEntryStore}. At
 * {@link SyncLoggingLevel#SUMMARY summary} the run keeps its counts only.
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
    /** Outcome recorded in the entry list for an in-scope source entry the set doesn't manage. */
    static final String NOT_IN_SET = "NOT_IN_SET";

    private final SyncReconcileRunRepository runRepo;
    private final SyncRunEntryStore entryStore;
    private final SyncWriteAuditor auditor;
    private final ObjectMapper objectMapper;
    private final Duration staleAfter;
    private final int keepPerSet;
    private final int keepIncrementalPerSet;
    private final String instanceName;
    /**
     * Reconciles running on this instance (run id → run), whose progress —
     * position and outcome counts so far — {@link #flushProgress} writes on
     * its own short interval: a run saves its whole row only every
     * {@link #HEARTBEAT_INTERVAL}, and one slow entry (a large group) would
     * otherwise show nothing for its whole duration.
     */
    private final Map<UUID, Run> live = new ConcurrentHashMap<>();
    private final ScheduledExecutorService progressFlusher;

    /** As the full constructor, with progress written every second. */
    public SyncReconcileHistory(SyncReconcileRunRepository runRepo, SyncRunEntryStore entryStore,
                                SyncWriteAuditor auditor, ObjectMapper objectMapper, long staleAfterMs,
                                int keepPerSet, int keepIncrementalPerSet) {
        this(runRepo, entryStore, auditor, objectMapper, staleAfterMs, keepPerSet, keepIncrementalPerSet, 1000);
    }

    @Autowired
    public SyncReconcileHistory(SyncReconcileRunRepository runRepo,
                                SyncRunEntryStore entryStore,
                                SyncWriteAuditor auditor,
                                ObjectMapper objectMapper,
                                @Value("${ldapportal.sync.reconcile.run-stale-after-ms:900000}") long staleAfterMs,
                                @Value("${ldapportal.sync.reconcile.history-per-set:20}") int keepPerSet,
                                @Value("${ldapportal.sync.reconcile.incremental-history-per-set:50}")
                                int keepIncrementalPerSet,
                                @Value("${ldapportal.sync.reconcile.progress-ms:1000}") long progressMs) {
        this.runRepo = runRepo;
        this.entryStore = entryStore;
        this.auditor = auditor;
        this.objectMapper = objectMapper;
        this.staleAfter = Duration.ofMillis(Math.max(1, staleAfterMs));
        this.keepPerSet = Math.max(1, keepPerSet);
        this.keepIncrementalPerSet = Math.max(1, keepIncrementalPerSet);
        this.instanceName = resolveInstanceName();
        this.progressFlusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-reconcile-progress");
            t.setDaemon(true);
            return t;
        });
        long every = Math.max(1, progressMs);
        this.progressFlusher.scheduleWithFixedDelay(this::flushProgress, every, every, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        progressFlusher.shutdownNow();
    }

    /**
     * Write how far each reconcile running on this instance has got, and the
     * entry it's on, when either changed since the last write.
     */
    void flushProgress() {
        OffsetDateTime now = OffsetDateTime.now();
        live.forEach((id, run) -> {
            // Held while writing, and taken by complete/fail before their final
            // save, so a progress write can never land after the run's end.
            synchronized (run.progressLock) {
                if (run.finished) {
                    return;
                }
                int processed = run.liveProcessed;
                SyncRunCounts counts = run.liveCounts;
                SyncReconcilePhase phase = run.livePhase;
                String currentDn = run.liveDn;
                if (processed == run.writtenProcessed && phase == run.writtenPhase
                        && java.util.Objects.equals(currentDn, run.writtenDn)) {
                    return;
                }
                try {
                    runRepo.progress(id, processed, counts, phase, currentDn, now);
                    run.writtenProcessed = processed;
                    run.writtenPhase = phase;
                    run.writtenDn = currentDn;
                } catch (Exception ex) {
                    log.warn("Sync set {}: reconcile progress update failed: {}",
                            run.row.getSyncSetId(), ex.toString());
                }
            }
        });
    }

    /**
     * Start recording a reconcile of {@code set}, whose writes land in
     * {@code targetDirectoryId}. Never {@code null}: if the row can't be
     * written, the run is still tallied.
     */
    public Run start(SyncSet set, UUID targetDirectoryId, SyncReconcileTrigger trigger, String requestedBy) {
        Run run = newRun(set, targetDirectoryId, trigger, requestedBy, false);
        run.livePhase = SyncReconcilePhase.SCANNING;
        run.row.setPhase(SyncReconcilePhase.SCANNING);
        save(run);
        run.writtenPhase = SyncReconcilePhase.SCANNING;
        live.put(run.row.getId(), run);
        return run;
    }

    /** No progress write lands after this: the run is about to record its end. */
    private void stopProgress(Run run) {
        synchronized (run.progressLock) {
            run.finished = true;
            live.remove(run.row.getId());
        }
    }

    /** The run moved on to {@code phase}. */
    public void phase(Run run, SyncReconcilePhase phase) {
        run.livePhase = phase;
        run.row.setPhase(phase);
    }

    /** The run is about to recompute {@code dn} (shown while it's slow, e.g. a large group). */
    public void current(Run run, String dn) {
        run.liveDn = dn;
        run.row.setCurrentDn(dn);
    }

    /**
     * Count an in-scope source entry the set doesn't manage, and why: the
     * reason gap between a run's source entries and the set's managed ones.
     * Up to {@link #SAMPLE_LIMIT} source DNs per reason are kept whatever the
     * logging level; every one at {@link SyncLoggingLevel#DETAILED detailed}.
     */
    public void notInSet(Run run, String sourceDn, SyncNotInSetReason reason) {
        SyncReconcileRun r = run.row;
        r.setProcessed(r.getProcessed() + 1);
        run.liveProcessed = r.getProcessed();
        r.setNotInSet(r.getNotInSet() + 1);
        NotInSetGroup group = run.notInSet.computeIfAbsent(reason, k -> new NotInSetGroup());
        group.count++;
        if (group.samples.size() < SAMPLE_LIMIT && sourceDn != null) {
            group.samples.add(sourceDn);
        }
        if (sourceDn != null) {
            run.keep(NOT_IN_SET, sourceDn, reason.name());
        }
        run.liveCounts = SyncRunCounts.of(run.row);
        beat(run);
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
        row.setAllDnsRecorded(set.getLoggingLevel() == SyncLoggingLevel.DETAILED);
        return new Run(row, targetDirectoryId, labelsFor(set), deferred);
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
        run.liveProcessed = r.getProcessed();
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
            if (SAMPLED.contains(outcome.action()) && outcome.targetDn() != null && r.isAllDnsRecorded()) {
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
        run.liveCounts = SyncRunCounts.of(run.row);
        beat(run);
    }

    /**
     * Count an entry whose recompute threw (it retries on the next run).
     * {@code key} — the source DN or identity being recomputed — stands in for
     * the target DN in the full entry list, which the fault never reached.
     */
    public void fault(Run run, Exception ex, String key) {
        run.row.setProcessed(run.row.getProcessed() + 1);
        run.liveProcessed = run.row.getProcessed();
        run.row.setFaulted(run.row.getFaulted() + 1);
        String msg = ex.getMessage();
        String reason = "recompute error: " + ex.getClass().getSimpleName()
                + (msg == null || msg.isBlank() ? "" : SEPARATOR + msg);
        run.countFailure(reason);
        run.noteworthy = true;
        if (key != null) {
            run.keep(FAULT, key, reason);
        }
        run.liveCounts = SyncRunCounts.of(run.row);
        beat(run);
    }

    /** The run reached its end. */
    public void complete(Run run, long managedAfter) {
        OffsetDateTime now = OffsetDateTime.now();
        stopProgress(run);
        run.row.setStatus(SyncReconcileRunStatus.COMPLETE);
        run.row.setPhase(null);
        run.row.setCurrentDn(null);
        run.row.setFinishedAt(now);
        run.row.setHeartbeatAt(now);
        run.row.setManagedAfter(managedAfter);
        save(run);
        prune(run.row.getSyncSetId(), true);
    }

    /** The run aborted on an unexpected error. */
    public void fail(Run run, Exception ex) {
        OffsetDateTime now = OffsetDateTime.now();
        stopProgress(run);
        run.row.setStatus(SyncReconcileRunStatus.FAILED);
        run.row.setPhase(null);
        run.row.setCurrentDn(null);
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
     * @throws IllegalArgumentException when the run didn't keep its DNs
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
     * @throws IllegalArgumentException when the run didn't keep its DNs
     */
    public void requireAllDnsRecorded(UUID syncSetId, UUID runId) {
        SyncReconcileRun r = runRepo.findById(runId)
                .filter(run -> run.getSyncSetId().equals(syncSetId))
                .orElseThrow(() -> new ResourceNotFoundException("Reconcile run not found"));
        if (!r.isAllDnsRecorded()) {
            throw new IllegalArgumentException("This run didn't keep its entries' DNs: its sync set's logging "
                    + "level wasn't Detailed when it ran.");
        }
    }

    /**
     * The in-scope source entries the set doesn't manage, as its latest
     * complete reconcile found them: every one when that run recorded all DNs,
     * else the samples it kept per reason. Optionally only those whose DN
     * contains {@code search}.
     */
    public com.ldapportal.dto.sync.SyncNotInSetPage notInSet(UUID syncSetId, String search, int page, int size) {
        int p = Math.max(0, page);
        int n = Math.max(1, Math.min(size, 200));
        SyncReconcileRun r = runRepo.findCompletedScans(syncSetId, SyncReconcileTrigger.RECONCILES,
                PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        if (r == null) {
            return new com.ldapportal.dto.sync.SyncNotInSetPage(null, null, false, 0, List.of(), List.of(), 0,
                    p, n, SAMPLE_LIMIT);
        }
        List<SyncNotInSetGroup> groups = notInSetGroups(r);
        List<com.ldapportal.dto.sync.SyncNotInSetPage.Entry> entries;
        long matched;
        if (r.isAllDnsRecorded()) {
            matched = entryStore.count(r.getId(), NOT_IN_SET, search);
            entries = entryStore.page(r.getId(), NOT_IN_SET, search, p * n, n).stream()
                    .map(e -> new com.ldapportal.dto.sync.SyncNotInSetPage.Entry(e.targetDn(), e.reason(),
                            labelOf(e.reason())))
                    .toList();
        } else {
            String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase(java.util.Locale.ROOT);
            List<com.ldapportal.dto.sync.SyncNotInSetPage.Entry> all = new ArrayList<>();
            for (SyncNotInSetGroup g : groups) {
                for (String dn : g.samples()) {
                    if (needle == null || dn.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                        all.add(new com.ldapportal.dto.sync.SyncNotInSetPage.Entry(dn, g.reason(), g.label()));
                    }
                }
            }
            matched = all.size();
            int from = Math.min(all.size(), p * n);
            entries = List.copyOf(all.subList(from, Math.min(all.size(), from + n)));
        }
        return new com.ldapportal.dto.sync.SyncNotInSetPage(r.getId(), r.getFinishedAt(), r.isAllDnsRecorded(),
                r.getNotInSet(), groups, entries, matched, p, n, SAMPLE_LIMIT);
    }

    private static String labelOf(String reason) {
        try {
            return SyncNotInSetReason.valueOf(reason).label();
        } catch (Exception ex) {
            return reason;
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
        return new SyncReconcileRunDetail(summary, settings, reasons, samples, SAMPLE_LIMIT, notInSetGroups(r));
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
                r.getVerifyMissing(), r.getVerifyOrphan(), r.getVerifyMismatch(), r.isAllDnsRecorded(),
                r.getNotInSet(), status == SyncReconcileRunStatus.RUNNING ? r.getPhase() : null,
                status == SyncReconcileRunStatus.RUNNING ? r.getCurrentDn() : null, r.getHeartbeatAt());
    }

    /** A run's not-in-set entries per reason, most first. */
    List<SyncNotInSetGroup> notInSetGroups(SyncReconcileRun r) {
        return fromJson(r.getNotInSetReasons(), new TypeReference<List<SyncNotInSetGroup>>() { }, List.of());
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
                    run.labels, outcome);
        } catch (Exception ex) {
            log.warn("Sync set {}: could not audit the write to {}: {}",
                    run.row.getSyncSetId(), outcome.targetDn(), ex.toString());
        }
    }

    private SyncAuditLabels labelsFor(SyncSet set) {
        try {
            SyncAuditLabels labels = auditor.labelsFor(set);
            return labels != null ? labels : SyncAuditLabels.NONE;
        } catch (Exception ex) {
            log.warn("Sync set {}: could not resolve the names for its audit records: {}", set.getId(), ex.toString());
            return SyncAuditLabels.NONE;
        }
    }

    private void save(Run run) {
        if (run.deferred) {
            return;
        }
        try {
            run.row.setSamples(run.samples.isEmpty() ? null : toJson(run.samples));
            run.row.setFailureReasons(run.failures.isEmpty() ? null : toJson(run.failureReasons()));
            run.row.setNotInSetReasons(run.notInSet.isEmpty() ? null : toJson(run.notInSetGroups()));
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
        /** The set and directory names its audit records carry. */
        private final SyncAuditLabels labels;
        /** Outcome name → sample target DNs. */
        private final Map<String, List<String>> samples = new LinkedHashMap<>();
        /** Reason kind → count and one full example. */
        private final Map<String, FailureGroup> failures = new LinkedHashMap<>();
        /** Not-in-set entries per reason, with sample source DNs. */
        private final Map<SyncNotInSetReason, NotInSetGroup> notInSet = new java.util.EnumMap<>(SyncNotInSetReason.class);
        /** Progress as the worker leaves it, read by {@link #flushProgress}. */
        private volatile int liveProcessed;
        /** Each outcome's count so far, published by the worker after every entry. */
        private volatile SyncRunCounts liveCounts = SyncRunCounts.NONE;
        private volatile SyncReconcilePhase livePhase;
        private volatile String liveDn;
        private final Object progressLock = new Object();
        /** Set (under {@link #progressLock}) once the run records its end. */
        private boolean finished;
        /** What {@link #flushProgress} last wrote (under {@link #progressLock}). */
        private int writtenProcessed;
        private SyncReconcilePhase writtenPhase;
        private String writtenDn;
        /** Entries kept since the last flush (only when the run records all DNs). */
        private final List<SyncRunEntryStore.Entry> pending = new ArrayList<>();
        private int nextSeq;
        /** An incremental pass not yet saved: nothing is written until it proves noteworthy. */
        private boolean deferred;
        /** It wrote to the target or flagged an entry. */
        private boolean noteworthy;

        private Run(SyncReconcileRun row, UUID targetDirectoryId, SyncAuditLabels labels, boolean deferred) {
            this.row = row;
            this.targetDirectoryId = targetDirectoryId;
            this.labels = labels;
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

        private List<SyncNotInSetGroup> notInSetGroups() {
            return notInSet.entrySet().stream()
                    .map(e -> new SyncNotInSetGroup(e.getKey().name(), e.getKey().label(), e.getValue().count,
                            List.copyOf(e.getValue().samples)))
                    .sorted(Comparator.comparingInt(SyncNotInSetGroup::count).reversed())
                    .toList();
        }

        private List<SyncRunFailureReason> failureReasons() {
            return failures.entrySet().stream()
                    .map(e -> new SyncRunFailureReason(e.getKey(), e.getValue().count, e.getValue().example))
                    .sorted(Comparator.comparingInt(SyncRunFailureReason::count).reversed())
                    .toList();
        }
    }

    private static final class NotInSetGroup {
        private final List<String> samples = new ArrayList<>();
        private int count;
    }

    private static final class FailureGroup {
        private final String example;
        private int count;

        private FailureGroup(String example) {
            this.example = example.length() > 500 ? example.substring(0, 497) + "…" : example;
        }
    }
}
