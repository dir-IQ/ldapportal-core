// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncPreviewAttributeChange;
import com.ldapportal.dto.sync.SyncPreviewEntry;
import com.ldapportal.dto.sync.SyncPreviewPage;
import com.ldapportal.dto.sync.SyncPreviewStatus;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.Membership;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncPreviewEntryRow;
import com.ldapportal.entity.SyncPreviewRun;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.SyncPreviewRunStatus;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.sync.identity.IdentityStrategy;
import com.ldapportal.ldap.sync.identity.IdentityStrategyRegistry;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncPreviewEntryRowRepository;
import com.ldapportal.repository.SyncPreviewRunRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.SearchResultEntry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Per-entry reconcile preview: for every entry a reconcile of the set would
 * touch, what it would do — add, modify (with the attribute changes), move,
 * delete, hold for review — computed by dry-running the engine's own
 * {@link RecomputeEngine#previewApply decision path}, so the prediction can't
 * drift from the real behaviour. Nothing is written to either directory.
 *
 * <p>A preview is a background job. {@link #start} claims the set's run row
 * (at most one run per set at a time, across app instances) and hands the work
 * to a small bounded pool; the job plans the whole set (one source scan, as a
 * reconcile does), dry-runs every entry, and stores the results in display
 * order. {@link #page} then reads that stored plan, so paging is cheap and
 * every page comes from the same snapshot. Each entry is dry-run in isolation:
 * one that can't be predicted is stored as {@link SyncPreviewAction#ERROR} with
 * the reason, and the rest of the run carries on.
 *
 * <p>DN references (e.g. {@code member}) resolve through the index and then
 * through the placements this preview plans, approximating the end state a
 * reconcile reaches once its closure fan-out re-drives referrers. Works for a
 * disabled set or link (nothing is written).
 */
@Component
@Slf4j
public class SyncPreviewService {

    public static final int MAX_PAGE_SIZE = 200;

    /** Entries per stored batch. */
    static final int BATCH_SIZE = 100;

    static final String INTERRUPTED = "Interrupted: the server running this preview stopped before it finished.";
    static final String BUSY = "Too many previews are running on this server; try again in a minute.";

    private static final ReferenceResolver NO_RESOLVER = dn -> Optional.empty();
    private static final TypeReference<List<SyncPreviewAttributeChange>> CHANGES = new TypeReference<>() { };

    private final SyncSetRepository syncSetRepo;
    private final SyncLinkRepository syncLinkRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final IdentityStrategyRegistry identityStrategies;
    private final MembershipRepository membershipRepo;
    private final MembershipFunction membershipFunction;
    private final MembershipReferenceResolvers referenceResolvers;
    private final MembershipReconciler reconciler;
    private final RecomputeEngine engine;
    private final SyncPreviewRunRepository runRepo;
    private final SyncPreviewEntryRowRepository entryRepo;
    private final ObjectMapper objectMapper;
    private final Duration staleAfter;
    private final Duration retention;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeats;

    /** Runs executing on this instance (set id → run id), kept alive by {@link #heartbeat}. */
    private final Map<UUID, UUID> active = new ConcurrentHashMap<>();

    /**
     * Where each run executing on this instance has got to (run id → progress),
     * written by {@link #flushProgress} on its own short interval rather than
     * per stored batch: a set of a few slow entries (large groups) would
     * otherwise show no progress until it finished.
     */
    private final Map<UUID, Progress> progress = new ConcurrentHashMap<>();

    public SyncPreviewService(SyncSetRepository syncSetRepo,
                              SyncLinkRepository syncLinkRepo,
                              DirectoryConnectionRepository directoryRepo,
                              IdentityStrategyRegistry identityStrategies,
                              MembershipRepository membershipRepo,
                              MembershipFunction membershipFunction,
                              MembershipReferenceResolvers referenceResolvers,
                              MembershipReconciler reconciler,
                              RecomputeEngine engine,
                              SyncPreviewRunRepository runRepo,
                              SyncPreviewEntryRowRepository entryRepo,
                              ObjectMapper objectMapper,
                              @Value("${ldapportal.sync.preview.parallelism:2}") int parallelism,
                              @Value("${ldapportal.sync.preview.stale-after-ms:120000}") long staleAfterMs,
                              @Value("${ldapportal.sync.preview.heartbeat-ms:15000}") long heartbeatMs,
                              @Value("${ldapportal.sync.preview.progress-ms:1000}") long progressMs,
                              @Value("${ldapportal.sync.preview.retention-hours:24}") long retentionHours) {
        this.syncSetRepo = syncSetRepo;
        this.syncLinkRepo = syncLinkRepo;
        this.directoryRepo = directoryRepo;
        this.identityStrategies = identityStrategies;
        this.membershipRepo = membershipRepo;
        this.membershipFunction = membershipFunction;
        this.referenceResolvers = referenceResolvers;
        this.reconciler = reconciler;
        this.engine = engine;
        this.runRepo = runRepo;
        this.entryRepo = entryRepo;
        this.objectMapper = objectMapper;
        this.staleAfter = Duration.ofMillis(Math.max(1, staleAfterMs));
        this.retention = Duration.ofHours(Math.max(1, retentionHours));
        int threads = Math.max(1, parallelism);
        // No queue: when every worker is busy a new run is refused at once rather
        // than waiting unseen (a queued run couldn't heartbeat and would look dead).
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), r -> {
                    Thread t = new Thread(r, "sync-preview");
                    t.setDaemon(true);
                    return t;
                });
        // Its own timer, not @Scheduled: the app's single scheduler thread also runs
        // the reconcile sweep, and a long sweep would starve the heartbeat until a
        // healthy run read as interrupted (and could be replaced).
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-preview-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long every = Math.max(1, heartbeatMs);
        this.heartbeats.scheduleWithFixedDelay(this::heartbeat, every, every, TimeUnit.MILLISECONDS);
        long progressEvery = Math.max(1, progressMs);
        this.heartbeats.scheduleWithFixedDelay(this::flushProgress, progressEvery, progressEvery,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        heartbeats.shutdownNow();
        executor.shutdownNow();
    }

    // ── API ────────────────────────────────────────────────────────────────────

    /** The set's latest preview run, or {@link SyncPreviewStatus#none()}. */
    public SyncPreviewStatus status(UUID syncSetId) {
        return runRepo.findById(syncSetId).map(this::toStatus).orElseGet(SyncPreviewStatus::none);
    }

    /**
     * Start a preview of the set in the background, unless one is already
     * running (then that run's status is returned, and nothing new starts).
     */
    public SyncPreviewStatus start(UUID syncSetId, String requestedBy) {
        UUID runId = claim(syncSetId, requestedBy);
        if (runId != null) {
            try {
                executor.execute(() -> execute(syncSetId, runId));
            } catch (RejectedExecutionException ex) {
                runRepo.fail(syncSetId, runId, OffsetDateTime.now(), BUSY);
            }
        }
        return status(syncSetId);
    }

    /** {@link #start}, but runs the job on the calling thread. For tests. */
    SyncPreviewStatus runNow(UUID syncSetId) {
        UUID runId = claim(syncSetId, null);
        if (runId != null) {
            execute(syncSetId, runId);
        }
        return status(syncSetId);
    }

    /**
     * One page of the set's completed preview. A page past the end returns the
     * last page (the listing may have shrunk since the caller last looked).
     *
     * @param page        zero-based page
     * @param size        page size, 1..{@value #MAX_PAGE_SIZE}
     * @param changesOnly leave out entries that wouldn't change (UNCHANGED, NO_CHANGE)
     * @throws IllegalArgumentException  for an out-of-range page or size
     * @throws ResourceNotFoundException when the set has no completed preview
     */
    public SyncPreviewPage page(UUID syncSetId, int page, int size, boolean changesOnly) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        SyncPreviewRun run = runRepo.findById(syncSetId)
                .filter(r -> r.getStatus() == SyncPreviewRunStatus.COMPLETE)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "This sync set has no completed preview; start one first."));
        long count = changesOnly ? run.getActionableCount() : run.getTotal();
        int totalPages = (int) ((count + size - 1) / size);
        int actual = totalPages == 0 ? 0 : Math.min(page, totalPages - 1);
        PageRequest request = PageRequest.of(actual, size);
        List<SyncPreviewEntryRow> rows = changesOnly
                ? entryRepo.findByRunIdAndActionableTrueOrderByOrdinalAsc(run.getRunId(), request)
                : entryRepo.findByRunIdOrderByOrdinalAsc(run.getRunId(), request);
        // A run started between the two reads deletes these entries as it begins;
        // report that rather than an empty page that contradicts the totals.
        boolean stillCurrent = runRepo.findById(syncSetId)
                .filter(r -> r.getStatus() == SyncPreviewRunStatus.COMPLETE && r.getRunId().equals(run.getRunId()))
                .isPresent();
        if (!stillCurrent) {
            throw new ResourceNotFoundException("This preview was replaced by a newer run; reload it.");
        }
        return new SyncPreviewPage(rows.stream().map(this::toEntry).toList(), actual, size, count, totalPages,
                changesOnly, run.getRunId());
    }

    // ── Housekeeping ───────────────────────────────────────────────────────────

    /** Keep this instance's running previews visibly alive, including through a long source scan. */
    void heartbeat() {
        OffsetDateTime now = OffsetDateTime.now();
        active.forEach((setId, runId) -> {
            try {
                runRepo.heartbeat(setId, runId, now);
            } catch (Exception ex) {
                log.warn("Sync set {}: preview heartbeat failed: {}", setId, ex.toString());
            }
        });
    }

    /**
     * Record how far each running preview on this instance has got, and which
     * entry it's on, when either changed since the last write. A run no longer
     * current is flagged, so its worker stops at its next entry.
     */
    void flushProgress() {
        OffsetDateTime now = OffsetDateTime.now();
        active.forEach((setId, runId) -> {
            Progress p = progress.get(runId);
            if (p == null || !p.changed()) {
                return;
            }
            try {
                int processed = p.processed;
                String currentDn = p.currentDn;
                if (runRepo.progress(setId, runId, processed, p.total, currentDn, now) == 0) {
                    p.superseded = true;
                } else {
                    p.written(processed, currentDn);
                }
            } catch (Exception ex) {
                log.warn("Sync set {}: preview progress update failed: {}", setId, ex.toString());
            }
        });
    }

    /** Drop previews past the retention: they hold attribute values and go stale anyway. */
    @Scheduled(fixedDelayString = "${ldapportal.sync.preview.purge-interval-ms:3600000}",
            initialDelayString = "${ldapportal.sync.preview.purge-interval-ms:3600000}")
    public void purge() {
        OffsetDateTime before = OffsetDateTime.now().minus(retention);
        entryRepo.deleteForRunsInactiveSince(before);
        int runs = runRepo.deleteInactiveSince(before);
        if (runs > 0) {
            log.info("Removed {} sync preview run(s) older than {}", runs, retention);
        }
    }

    // ── The job ────────────────────────────────────────────────────────────────

    /** Start a run: returns its id, or {@code null} when one is already running. */
    private UUID claim(UUID syncSetId, String requestedBy) {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        if (runRepo.claim(syncSetId, runId, requestedBy, now, now.minus(staleAfter)) == 1) {
            return runId;
        }
        if (runRepo.existsById(syncSetId)) {
            return null; // a live run holds it
        }
        SyncPreviewRun run = new SyncPreviewRun();
        run.setSyncSetId(syncSetId);
        run.setRunId(runId);
        run.setStatus(SyncPreviewRunStatus.RUNNING);
        run.setRequestedBy(requestedBy);
        run.setStartedAt(now);
        run.setHeartbeatAt(now);
        try {
            runRepo.saveAndFlush(run);
            return runId;
        } catch (DataIntegrityViolationException ex) {
            return null; // another request created the set's first run a moment earlier
        }
    }

    private void execute(UUID syncSetId, UUID runId) {
        active.put(syncSetId, runId);
        try {
            entryRepo.deleteOtherRuns(syncSetId, runId);
            String failure = plan(syncSetId, runId);
            if (failure != null) {
                runRepo.fail(syncSetId, runId, OffsetDateTime.now(), failure);
            }
        } catch (Superseded ex) {
            log.info("Sync set {}: preview run {} was superseded; stopping", syncSetId, runId);
        } catch (Exception ex) {
            log.warn("Sync set {}: preview run {} failed", syncSetId, runId, ex);
            runRepo.fail(syncSetId, runId, OffsetDateTime.now(), "The preview failed: " + message(ex));
        } finally {
            active.remove(syncSetId, runId);
            progress.remove(runId);
        }
    }

    /** Plan, dry-run and store the set. Returns a failure message, or {@code null} once complete. */
    private String plan(UUID syncSetId, UUID runId) {
        SyncSet set = syncSetRepo.findById(syncSetId).orElse(null);
        SyncLink link = set == null ? null : syncLinkRepo.findById(set.getLinkId()).orElse(null);
        DirectoryConnection source = link == null ? null : directoryRepo.findById(link.getSourceDirId()).orElse(null);
        DirectoryConnection target = link == null ? null : directoryRepo.findById(link.getTargetDirId()).orElse(null);
        if (set == null || link == null || source == null || target == null) {
            return "Sync set, link, or source/target directory is unavailable.";
        }
        IdentityStrategy strategy = identityStrategies.forType(source.getDirectoryType());
        if (SyncIdentity.attribute(set, strategy) == null) {
            return "The source directory type has no LDAP identity attribute.";
        }

        // ── Plan the whole set, as a reconcile would ──
        List<SearchResultEntry> enumerated;
        boolean complete = true;
        String note = null;
        try {
            enumerated = reconciler.enumerate(set, source, strategy);
        } catch (Exception ex) {
            log.warn("Sync set {}: preview source enumeration failed: {}", syncSetId, ex.toString());
            enumerated = List.of();
            complete = false;
            note = "Source enumeration failed (" + message(ex) + "); a reconcile would make no deletes.";
        }

        Map<String, Membership> rows = new HashMap<>();
        for (Membership m : membershipRepo.findAllBySyncSetId(set.getId())) {
            rows.put(m.getIdentity(), m);
        }
        List<Item> items = new ArrayList<>();
        Map<String, String> plannedPlacement = new HashMap<>();
        Set<String> plannedOut = new HashSet<>(); // source DNs this plan takes out of the set
        Set<String> seen = new HashSet<>();
        int identified = 0;
        int deletes = 0;
        for (SearchResultEntry e : enumerated) {
            MembershipDecision placement;
            try {
                placement = membershipFunction.evaluate(set, strategy, e, NO_RESOLVER);
            } catch (Exception ex) {
                // A reconcile's planning loop stops at the first entry it can't
                // evaluate and treats the scan as incomplete; predict exactly that.
                log.warn("Sync set {}: preview couldn't evaluate {}: {}", syncSetId, e.getDN(), ex.toString());
                complete = false;
                note = "Evaluating " + e.getDN() + " failed (" + message(ex) + "). A reconcile stops planning "
                        + "at this entry: entries after it in the source scan aren't listed, and no deletes "
                        + "would happen.";
                break;
            }
            String identity = placement.identity();
            if (identity == null || identity.isBlank()) {
                continue; // a reconcile can't track an entry without an identity either
            }
            identified++;
            seen.add(identity);
            Membership row = rows.get(identity);
            if (placement.member()) {
                plannedPlacement.put(SyncDnUtil.normalize(e.getDN()), placement.targetDn());
            } else if (row != null) {
                plannedOut.add(SyncDnUtil.normalize(e.getDN()));
                deletes++;
            } else {
                continue; // in scope but not in the set, and never synced: a reconcile ignores it
            }
            items.add(new Item(identity, e, placement.member(), placement.targetDn(), row));
        }
        if (complete) {
            for (Membership row : rows.values()) {
                if (!seen.contains(row.getIdentity())) {
                    items.add(new Item(row.getIdentity(), null, false, null, row)); // not seen this scan
                    deletes++;
                }
            }
        }
        MembershipReconciler.Guard guard = MembershipReconciler.guardFor(complete, identified, rows.size(), deletes);
        ReferenceResolver resolver = plannedResolver(referenceResolvers.forLink(link), plannedPlacement, plannedOut);
        items.sort(Comparator.comparing(Item::sortKey));

        // ── Dry-run every entry, storing the results in display order ──
        int total = items.size();
        String firstDn = total > 0 ? items.get(0).displayDn() : null;
        requireCurrent(runRepo.progress(syncSetId, runId, 0, total, firstDn, OffsetDateTime.now()));
        Progress p = new Progress(total, firstDn);
        progress.put(runId, p);
        List<SyncPreviewEntryRow> batch = new ArrayList<>(BATCH_SIZE);
        long actionable = 0;
        for (int i = 0; i < total; i++) {
            if (p.superseded) {
                throw new Superseded();
            }
            Item item = items.get(i);
            p.currentDn = item.displayDn();
            SyncPreviewEntry entry = dryRun(set, target, strategy, item, resolver, guard.tripped());
            p.processed = i + 1;
            boolean listed = isActionable(entry.action());
            if (listed) {
                actionable++;
            }
            batch.add(toRow(syncSetId, runId, i, entry, listed));
            if (batch.size() == BATCH_SIZE) {
                entryRepo.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            entryRepo.saveAll(batch);
        }
        requireCurrent(runRepo.complete(syncSetId, runId, OffsetDateTime.now(), total, identified, rows.size(),
                actionable, guard.tripped(), guard.reason(), complete, note, source.getId(), target.getId()));
        return null;
    }

    /**
     * This plan first, then the index: a referent the plan places resolves to
     * where the plan puts it (a move shows its referrers rewritten), and one the
     * plan takes out of the set doesn't resolve (its referrers lose it, as after
     * the reconcile's closure fan-out). Only the rest go to the index; those
     * lookups are memoised, so a popular group member is resolved once per run.
     */
    static ReferenceResolver plannedResolver(ReferenceResolver index, Map<String, String> plannedPlacement,
                                             Set<String> plannedOut) {
        Map<String, Optional<String>> resolved = new HashMap<>();
        return new ReferenceResolver() {
            @Override
            public Optional<String> resolveTargetDn(String dn) {
                String norm = SyncDnUtil.normalize(dn);
                String planned = plannedPlacement.get(norm);
                if (planned != null) {
                    return Optional.of(planned);
                }
                if (plannedOut.contains(norm)) {
                    return Optional.empty();
                }
                return resolved.computeIfAbsent(norm, k -> index.resolveTargetDn(dn));
            }

            @Override
            public ReferenceResolver prefetched(java.util.Collection<String> dns) {
                // Memoise, in one lookup, every one the plan doesn't decide and
                // this run hasn't resolved yet.
                List<String> rest = new ArrayList<>();
                for (String dn : dns) {
                    String norm = SyncDnUtil.normalize(dn);
                    if (!plannedPlacement.containsKey(norm) && !plannedOut.contains(norm)
                            && !resolved.containsKey(norm)) {
                        rest.add(dn);
                    }
                }
                if (rest.size() > 1) {
                    ReferenceResolver bulk = index.prefetched(rest);
                    for (String dn : rest) {
                        resolved.putIfAbsent(SyncDnUtil.normalize(dn), bulk.resolveTargetDn(dn));
                    }
                }
                return this;
            }

            @Override
            public boolean leavesItsSet(String dn) {
                return plannedOut.contains(SyncDnUtil.normalize(dn));
            }
        };
    }

    /** One entry's dry run, isolated: a failure becomes an ERROR entry, not a failed run. */
    private SyncPreviewEntry dryRun(SyncSet set, DirectoryConnection target, IdentityStrategy strategy, Item it,
                                    ReferenceResolver resolver, boolean suppressDeletes) {
        try {
            return engine.previewApply(set, target, strategy, it.identity, it.row, it.entry, resolver,
                    suppressDeletes);
        } catch (Exception ex) {
            log.warn("Sync set {}: preview of identity {} failed: {}", set.getId(), it.identity, ex.toString());
            String sourceDn = it.entry != null ? it.entry.getDN() : (it.row != null ? it.row.getSourceDn() : null);
            String targetDn = it.member ? it.targetDn : (it.row != null ? it.row.getTargetDn() : null);
            return new SyncPreviewEntry(it.identity, sourceDn, targetDn, null, SyncPreviewAction.ERROR,
                    "Couldn't predict this entry: " + message(ex), List.of());
        }
    }

    /** Whether the "changes only" view lists an entry: anything but a confirmed no-op. */
    static boolean isActionable(SyncPreviewAction action) {
        return action != SyncPreviewAction.UNCHANGED && action != SyncPreviewAction.NO_CHANGE;
    }

    private static void requireCurrent(int updated) {
        if (updated == 0) {
            throw new Superseded();
        }
    }

    /** The run was replaced (or reclaimed as stale) while this worker ran it. */
    private static final class Superseded extends RuntimeException {
        Superseded() {
            super(null, null, false, false);
        }
    }

    // ── Mapping ────────────────────────────────────────────────────────────────

    private SyncPreviewStatus toStatus(SyncPreviewRun run) {
        SyncPreviewRunStatus status = run.getStatus();
        String error = run.getError();
        if (status == SyncPreviewRunStatus.RUNNING
                && run.getHeartbeatAt().isBefore(OffsetDateTime.now().minus(staleAfter))) {
            status = SyncPreviewRunStatus.FAILED;
            error = INTERRUPTED;
        }
        boolean done = status == SyncPreviewRunStatus.COMPLETE;
        boolean outdated = done && isOutdated(run);
        boolean running = status == SyncPreviewRunStatus.RUNNING;
        return new SyncPreviewStatus(run.getRunId(), status, run.getRequestedBy(), run.getStartedAt(),
                run.getFinishedAt(), run.getProcessed(), run.getTotal(),
                running ? run.getCurrentDn() : null, run.getHeartbeatAt(), OffsetDateTime.now(), outdated, error,
                run.getSourceCount(), run.getManagedCount(), run.getActionableCount(),
                done ? run.getTotal() - run.getActionableCount() : 0,
                run.isGuardTripped(), run.getGuardReason(), run.isCompleteScan(), run.getNote(),
                done ? actionCounts(run.getRunId()) : Map.of());
    }

    /** Outcome name → entries, in the action enum's order (zero counts left out). */
    private Map<String, Long> actionCounts(UUID runId) {
        Map<SyncPreviewAction, Long> byAction = new java.util.EnumMap<>(SyncPreviewAction.class);
        for (Object[] row : entryRepo.countByAction(runId)) {
            byAction.put((SyncPreviewAction) row[0], (Long) row[1]);
        }
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        byAction.forEach((action, n) -> out.put(action.name(), n));
        return out;
    }

    /**
     * Whether a completed run no longer describes the set: the set was edited or
     * reconciled after the run started, its link now points at other
     * directories, or either directory's connection settings were edited since.
     * (The link's own timestamp can't be used: the changelog poller saves the
     * link on every poll.)
     */
    private boolean isOutdated(SyncPreviewRun run) {
        OffsetDateTime started = run.getStartedAt();
        SyncSet set = syncSetRepo.findById(run.getSyncSetId()).orElse(null);
        if (set == null || changedSince(set.getUpdatedAt(), started)) {
            return true;
        }
        SyncLink link = syncLinkRepo.findById(set.getLinkId()).orElse(null);
        if (link == null || !link.getSourceDirId().equals(run.getSourceDirId())
                || !link.getTargetDirId().equals(run.getTargetDirId())) {
            return true;
        }
        for (UUID dirId : List.of(link.getSourceDirId(), link.getTargetDirId())) {
            DirectoryConnection dir = directoryRepo.findById(dirId).orElse(null);
            if (dir == null || changedSince(dir.getUpdatedAt(), started)) {
                return true;
            }
        }
        return false;
    }

    private static boolean changedSince(OffsetDateTime updated, OffsetDateTime since) {
        return updated != null && updated.isAfter(since);
    }

    private SyncPreviewEntryRow toRow(UUID syncSetId, UUID runId, int ordinal, SyncPreviewEntry e,
                                      boolean actionable) {
        SyncPreviewEntryRow row = new SyncPreviewEntryRow();
        row.setRunId(runId);
        row.setOrdinal(ordinal);
        row.setSyncSetId(syncSetId);
        row.setIdentity(e.identity());
        row.setSourceDn(e.sourceDn());
        row.setTargetDn(e.targetDn());
        row.setPreviousTargetDn(e.previousTargetDn());
        row.setAction(e.action());
        row.setReason(e.reason());
        row.setActionable(actionable);
        if (!e.changes().isEmpty()) {
            try {
                row.setChanges(objectMapper.writeValueAsString(e.changes()));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Couldn't serialise preview changes", ex);
            }
        }
        return row;
    }

    private SyncPreviewEntry toEntry(SyncPreviewEntryRow row) {
        List<SyncPreviewAttributeChange> changes = List.of();
        if (row.getChanges() != null) {
            try {
                changes = objectMapper.readValue(row.getChanges(), CHANGES);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Stored preview changes are unreadable", ex);
            }
        }
        return new SyncPreviewEntry(row.getIdentity(), row.getSourceDn(), row.getTargetDn(),
                row.getPreviousTargetDn(), row.getAction(), row.getReason(), changes);
    }

    private static String message(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.toString();
    }

    /**
     * Orders DNs so a parent sorts directly before its subtree: compare RDNs from
     * the root down. A reconcile can create entries in this order.
     */
    static String hierarchyKey(String dn) {
        if (dn == null) {
            return "";
        }
        try {
            RDN[] rdns = new DN(dn).getRDNs();
            StringBuilder sb = new StringBuilder(dn.length() + rdns.length);
            for (int i = rdns.length - 1; i >= 0; i--) {
                sb.append(rdns[i].toNormalizedString()).append('\u0001');
            }
            return sb.toString();
        } catch (LDAPException e) {
            return dn.toLowerCase(Locale.ROOT);
        }
    }

    private record Item(String identity, SearchResultEntry entry, boolean member, String targetDn, Membership row) {
        String sortKey() {
            String dn = member ? targetDn
                    : (row != null && row.getTargetDn() != null ? row.getTargetDn()
                    : (entry != null ? entry.getDN() : identity));
            return hierarchyKey(dn);
        }

        /** The DN the progress line names: the source entry, or the target entry of one gone from the source. */
        String displayDn() {
            if (entry != null) {
                return entry.getDN();
            }
            if (row != null) {
                return row.getTargetDn() != null ? row.getTargetDn() : row.getSourceDn();
            }
            return identity;
        }
    }

    /**
     * A run's progress, updated by its worker as it goes and written by
     * {@link #flushProgress}; {@code superseded} is set when that write finds
     * the run no longer current.
     */
    private static final class Progress {
        final int total;
        volatile int processed;
        volatile String currentDn;
        volatile boolean superseded;
        private int writtenProcessed;
        private String writtenDn;

        Progress(int total, String currentDn) {
            this.total = total;
            this.currentDn = currentDn;
            this.writtenDn = currentDn; // the worker wrote this before registering
        }

        boolean changed() {
            return processed != writtenProcessed || !java.util.Objects.equals(currentDn, writtenDn);
        }

        void written(int processed, String currentDn) {
            this.writtenProcessed = processed;
            this.writtenDn = currentDn;
        }
    }
}
