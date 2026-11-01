// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.PageResponse;
import com.ldapportal.dto.diff.DirectoryDiffAttributeDiff;
import com.ldapportal.dto.diff.DirectoryDiffEntryDetail;
import com.ldapportal.dto.diff.DirectoryDiffEntryResponse;
import com.ldapportal.dto.diff.DirectoryDiffLiveEntry;
import com.ldapportal.dto.diff.DirectoryDiffRequest;
import com.ldapportal.dto.diff.DirectoryDiffRunResponse;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.DirectoryDiffRun;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.DirectoryDiffCategory;
import com.ldapportal.entity.enums.DirectoryDiffPairing;
import com.ldapportal.entity.enums.DirectoryDiffPhase;
import com.ldapportal.entity.enums.DirectoryDiffStatus;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.diff.DirectoryDiffEngine;
import com.ldapportal.ldap.sync.SyncTargetSchemas;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.DirectoryDiffEntryStore;
import com.ldapportal.repository.DirectoryDiffRunRepository;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPInterface;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Directory diff: compares two directories, or two branches of one, entry by
 * entry, and sorts every entry into Same / Different / Only in A / Only in B.
 *
 * <p>A run reads both sides in pages, pairs their entries (by DN below each
 * base, or by an attribute's value) and compares each pair with the sync
 * engine's rules ({@link DirectoryDiffEngine}). It runs in the background: the
 * request returns at once, and the run's row reports its phase, progress and
 * counts as it goes. Read-only: nothing is written to either directory.
 * Operational attributes and passwords are never compared or stored.
 */
@Service
@Slf4j
public class DirectoryDiffService {

    /** A RUNNING run silent this long belonged to a server that stopped. */
    static final Duration STALE_AFTER = Duration.ofMinutes(2);
    /** How often a run writes its progress. */
    private static final long FLUSH_NANOS = 1_000_000_000L;
    /** Rows written per batch. */
    private static final int WRITE_BATCH = 500;
    private static final String INTERRUPTED = "Interrupted: the server running it stopped. Run it again.";
    private static final Pattern ATTR_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9-]*(;[A-Za-z0-9-]+)*$");

    private final DirectoryDiffRunRepository runRepo;
    private final DirectoryDiffEntryStore entryStore;
    private final DirectoryConnectionRepository directoryRepo;
    private final LdapConnectionFactory connectionFactory;
    private final SyncTargetSchemas schemas;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final int maxConcurrent;
    private final int maxEntriesPerSide;
    private final int keepPerPair;
    /** Roughly how much heap one run may fill with the entries it reads. */
    private final long memoryBudgetBytes;
    private final ExecutorService runs;
    /** Serialises this server's check-then-start, so its own starts can't race past the limit. */
    private final Object startLock = new Object();

    public DirectoryDiffService(DirectoryDiffRunRepository runRepo, DirectoryDiffEntryStore entryStore,
                                DirectoryConnectionRepository directoryRepo, LdapConnectionFactory connectionFactory,
                                SyncTargetSchemas schemas, AuditService auditService, ObjectMapper objectMapper,
                                @Value("${ldapportal.directory-diff.max-concurrent:2}") int maxConcurrent,
                                @Value("${ldapportal.directory-diff.max-entries-per-side:200000}") int maxEntriesPerSide,
                                @Value("${ldapportal.directory-diff.keep-per-pair:10}") int keepPerPair,
                                @Value("${ldapportal.directory-diff.memory-percent:40}") int memoryPercent,
                                @Value("${ldapportal.directory-diff.memory-budget-mb:0}") long memoryBudgetMb) {
        this.runRepo = runRepo;
        this.entryStore = entryStore;
        this.directoryRepo = directoryRepo;
        this.connectionFactory = connectionFactory;
        this.schemas = schemas;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.maxEntriesPerSide = Math.max(1, maxEntriesPerSide);
        this.keepPerPair = Math.max(1, keepPerPair);
        this.memoryBudgetBytes = memoryBudgetMb > 0 ? memoryBudgetMb * 1024 * 1024
                : Runtime.getRuntime().maxMemory() / 100 * Math.min(Math.max(memoryPercent, 1), 80) / this.maxConcurrent;
        // Not a fixed pool: a run that waited in a queue would send no heartbeat and read as
        // interrupted. start() keeps the count of runs to max-concurrent.
        this.runs = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "directory-diff");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        runs.shutdownNow();
    }

    // ── Starting a run ───────────────────────────────────────────────────────────

    /**
     * The request as it will run: each side resolved (directory, base, scope,
     * filter) and the attribute choices cleaned up. Kept as the run's settings.
     */
    record Settings(Side a, Side b, DirectoryDiffPairing pairBy, String pairAttribute, List<String> attributes,
                    List<String> ignore) {
    }

    record Side(java.util.UUID directoryId, String baseDn, String scope, String filter) {
    }

    /**
     * Start comparing, in the background, and return the new run.
     *
     * @throws IllegalArgumentException for a request that can't run (an unknown scope, a filter or DN that
     *                                  doesn't parse, a base outside the directory, an Entra ID directory, two
     *                                  identical sides)
     * @throws ConflictException        while {@code max-concurrent} runs are already going
     */
    public DirectoryDiffRunResponse start(DirectoryDiffRequest req, AuthPrincipal principal) {
        if (req == null || req.a() == null || req.b() == null) {
            throw new IllegalArgumentException("Choose a directory for each side.");
        }
        DirectoryConnection dirA = directory(req.a().directoryId());
        DirectoryConnection dirB = directory(req.b().directoryId());
        Side a = side(req.a(), dirA, "A");
        Side b = side(req.b(), dirB, "B");
        if (a.directoryId().equals(b.directoryId()) && sameDn(a.baseDn(), b.baseDn())
                && a.scope().equals(b.scope()) && a.filter().equals(b.filter())) {
            throw new IllegalArgumentException("The two sides are the same: choose another directory, base DN or filter.");
        }
        DirectoryDiffPairing pairBy = req.pairBy() != null ? req.pairBy() : DirectoryDiffPairing.DN;
        String pairAttribute = null;
        if (pairBy == DirectoryDiffPairing.ATTRIBUTE) {
            pairAttribute = req.pairAttribute() == null ? "" : req.pairAttribute().trim();
            if (!ATTR_NAME.matcher(pairAttribute).matches()) {
                throw new IllegalArgumentException("Name the attribute to pair entries by (e.g. employeeNumber).");
            }
        }
        Settings settings = new Settings(a, b, pairBy, pairAttribute, names(req.attributes(), "attributes"),
                names(req.ignore(), "ignore"));

        DirectoryDiffRun run = new DirectoryDiffRun();
        OffsetDateTime now;
        synchronized (startLock) {
            now = OffsetDateTime.now();
            if (runRepo.countRunning(now.minus(STALE_AFTER)) >= maxConcurrent) {
                throw new ConflictException(maxConcurrent + " directory comparisons are already running. "
                        + "Wait for one to finish, or cancel one.");
            }
            run.setId(UUID.randomUUID());
            run.setRequestedBy(principal != null ? principal.username() : null);
            run.setStatus(DirectoryDiffStatus.RUNNING);
            run.setPhase(DirectoryDiffPhase.READING_A);
            run.setStartedAt(now);
            run.setHeartbeatAt(now);
            run.setDirectoryAId(dirA.getId());
            run.setDirectoryBId(dirB.getId());
            run.setDirectoryAName(dirA.getDisplayName());
            run.setDirectoryBName(dirB.getDisplayName());
            run.setBaseA(a.baseDn());
            run.setBaseB(b.baseDn());
            run.setPairing(pairBy);
            run.setSettings(toJson(settings));
            runRepo.saveAndFlush(run);
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", "directory-diff");
        detail.put("runId", run.getId().toString());
        detail.put("directoryB", dirB.getDisplayName());
        detail.put("baseB", b.baseDn());
        detail.put("pairBy", pairBy.name() + (pairAttribute != null ? " " + pairAttribute : ""));
        auditService.record(principal, dirA.getId(), AuditAction.INTEGRITY_CHECK, a.baseDn(), detail);

        UUID id = run.getId();
        runs.execute(() -> execute(id, settings, dirA, dirB));
        log.info("Directory diff {} started by {}: {} ({}) ↔ {} ({})", id, run.getRequestedBy(),
                dirA.getDisplayName(), a.baseDn(), dirB.getDisplayName(), b.baseDn());
        return toResponse(run);
    }

    /** Ask a running comparison to stop; it stops at its next progress write. */
    public DirectoryDiffRunResponse cancel(UUID id) {
        DirectoryDiffRun run = require(id);
        if (run.getStatus() != DirectoryDiffStatus.RUNNING) {
            throw new IllegalArgumentException("This comparison isn't running.");
        }
        runRepo.requestCancel(id);
        return toResponse(require(id));
    }

    private DirectoryConnection directory(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("Choose a directory for each side.");
        }
        DirectoryConnection dc = directoryRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Directory not found: " + id));
        if (dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            throw new IllegalArgumentException(dc.getDisplayName()
                    + " is an Entra ID directory; only LDAP directories can be compared.");
        }
        return dc;
    }

    private static Side side(DirectoryDiffRequest.Side req, DirectoryConnection dc, String label) {
        String base = req.baseDn() == null || req.baseDn().isBlank() ? dc.getBaseDn() : req.baseDn().trim();
        DN parsed;
        try {
            parsed = new DN(base);
        } catch (LDAPException ex) {
            throw new IllegalArgumentException("Side " + label + "'s base DN isn't a valid DN: " + base);
        }
        boolean underRoot = false;
        for (String root : dc.getBaseDns()) {
            try {
                if (parsed.isDescendantOf(new DN(root), true)) {
                    underRoot = true;
                    break;
                }
            } catch (LDAPException ignored) {
                // a root that doesn't parse can't contain it
            }
        }
        if (!underRoot) {
            throw new IllegalArgumentException("Side " + label + "'s base DN '" + base + "' isn't under any of "
                    + dc.getDisplayName() + "'s base DNs.");
        }
        String scope = req.scope() == null || req.scope().isBlank() ? "SUB" : req.scope().trim().toUpperCase(Locale.ROOT);
        if (!scope.equals("SUB") && !scope.equals("ONE")) {
            throw new IllegalArgumentException("Side " + label + "'s scope must be SUB or ONE.");
        }
        String filter = req.filter() == null || req.filter().isBlank() ? "(objectClass=*)" : req.filter().trim();
        try {
            Filter.create(filter);
        } catch (LDAPException ex) {
            throw new IllegalArgumentException("Side " + label + "'s filter isn't a valid LDAP filter: " + filter);
        }
        return new Side(dc.getId(), base, scope, filter);
    }

    private static List<String> names(List<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v == null || v.isBlank()) {
                continue;
            }
            String name = v.trim();
            if (!ATTR_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("'" + name + "' in " + field + " isn't an attribute name.");
            }
            out.add(name);
        }
        return List.copyOf(out);
    }

    // ── Running ──────────────────────────────────────────────────────────────────

    /** Thrown inside a run when a cancel was asked for. */
    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super(null, null, false, false);
        }
    }

    /** The run as it goes: its row, and when it last wrote progress. */
    private final class Tracker {
        private final DirectoryDiffRun run;
        private long lastFlush = System.nanoTime();
        private boolean cancelRequested;
        /** About how much heap the entries read so far take, both sides together. */
        private long bytes;

        Tracker(DirectoryDiffRun run) {
            this.run = run;
        }

        /** Write progress if it's due, and stop if a cancel was asked for. */
        void tick() {
            if (System.nanoTime() - lastFlush >= FLUSH_NANOS) {
                flush();
            }
            if (cancelRequested) {
                throw new Cancelled();
            }
        }

        void flush() {
            lastFlush = System.nanoTime();
            run.setHeartbeatAt(OffsetDateTime.now());
            runRepo.save(run);
            cancelRequested = Boolean.TRUE.equals(runRepo.isCancelRequested(run.getId()));
        }
    }

    private void execute(UUID id, Settings settings, DirectoryConnection dirA, DirectoryConnection dirB) {
        DirectoryDiffRun run = runRepo.findById(id).orElse(null);
        if (run == null) {
            return;
        }
        Tracker tracker = new Tracker(run);
        try {
            String[] requested = requestedAttributes(settings);
            Predicate<String> kept = kept(settings);
            List<Entry> a = read(dirA, settings.a(), "A", requested, kept, tracker);
            run.setPhase(DirectoryDiffPhase.READING_B);
            run.setProcessed(0);
            tracker.flush();
            List<Entry> b = read(dirB, settings.b(), "B", requested, kept, tracker);

            run.setPhase(DirectoryDiffPhase.COMPARING);
            run.setProcessed(0);
            run.setTotal(a.size() + b.size());
            tracker.flush();
            compare(run, settings, a, b, schemas.forDirectory(dirB), tracker);

            run.setStatus(DirectoryDiffStatus.COMPLETE);
            finish(run);
            log.info("Directory diff {} finished: {} same, {} different, {} only in A, {} only in B", id,
                    run.getSameCount(), run.getDifferentCount(), run.getOnlyACount(), run.getOnlyBCount());
        } catch (Cancelled ex) {
            run.setStatus(DirectoryDiffStatus.CANCELLED);
            finish(run);
            log.info("Directory diff {} cancelled", id);
        } catch (Exception ex) {
            run.setStatus(DirectoryDiffStatus.FAILED);
            run.setError(message(ex));
            finish(run);
            log.warn("Directory diff {} failed: {}", id, ex.toString());
        }
        prune(run.getDirectoryAId(), run.getDirectoryBId());
    }

    private void finish(DirectoryDiffRun run) {
        OffsetDateTime now = OffsetDateTime.now();
        run.setPhase(DirectoryDiffPhase.DONE);
        run.setCurrentDn(null);
        run.setFinishedAt(now);
        run.setHeartbeatAt(now);
        try {
            runRepo.save(run);
        } catch (Exception ex) {
            log.warn("Directory diff {}: could not record its end: {}", run.getId(), ex.toString());
        }
    }

    /** Every user attribute, plus the ones named that "*" doesn't return (e.g. an operational pairing attribute). */
    private static String[] requestedAttributes(Settings settings) {
        Set<String> out = new LinkedHashSet<>();
        out.add("*");
        out.addAll(settings.attributes());
        if (settings.pairAttribute() != null) {
            out.add(settings.pairAttribute());
        }
        return out.toArray(new String[0]);
    }

    /**
     * The attributes a run keeps of each entry it reads (lower-case base names):
     * those it compares, and the one it pairs by. Everything else (operational
     * attributes, passwords, ignored ones) is dropped as soon as a page arrives.
     */
    private static Predicate<String> kept(Settings settings) {
        Predicate<String> compared = DirectoryDiffEngine.compared(settings.attributes(), settings.ignore());
        String pairBy = settings.pairAttribute() == null ? null : settings.pairAttribute().toLowerCase(Locale.ROOT);
        return name -> compared.test(name) || name.equals(pairBy);
    }

    /** The entry, keeping only its DN and the {@code kept} attributes. */
    static Entry compact(Entry e, Predicate<String> kept) {
        List<Attribute> attrs = new ArrayList<>();
        for (Attribute attr : e.getAttributes()) {
            if (kept.test(attr.getBaseName().toLowerCase(Locale.ROOT))) {
                attrs.add(attr);
            }
        }
        return new Entry(e.getDN(), attrs);
    }

    /**
     * A rough estimate of the heap an entry takes once read: the DN, each
     * attribute's name and each value, with the JVM's per-object overhead.
     * Errs high, so a budget built on it errs safe.
     */
    static long estimateBytes(Entry e) {
        long bytes = 96 + 2L * e.getDN().length();
        for (Attribute attr : e.getAttributes()) {
            bytes += 96 + 2L * attr.getName().length();
            for (byte[] value : attr.getValueByteArrays()) {
                bytes += 64 + value.length;
            }
        }
        return bytes;
    }

    /** Read one side completely, a page at a time, counting as it goes. */
    private List<Entry> read(DirectoryConnection dc, Side side, String label, String[] attributes,
                             Predicate<String> kept, Tracker tracker) throws LDAPException {
        SearchRequest request = new SearchRequest(side.baseDn(),
                side.scope().equals("ONE") ? SearchScope.ONE : SearchScope.SUB, Filter.create(side.filter()), attributes);
        int pageSize = dc.getPagingSize() > 0 ? dc.getPagingSize() : 500;
        return connectionFactory.withConnection(dc,
                conn -> readPages(conn, request, pageSize, label, kept, tracker, dc));
    }

    private List<Entry> readPages(LDAPInterface conn, SearchRequest request, int pageSize, String label,
                                  Predicate<String> kept, Tracker tracker, DirectoryConnection dc)
            throws LDAPException {
        List<Entry> out = new ArrayList<>();
        ASN1OctetString cookie = null;
        do {
            SearchRequest page = request.duplicate();
            page.setControls(new SimplePagedResultsControl(pageSize, cookie, false));
            SearchResult result;
            try {
                result = conn.search(page);
            } catch (LDAPException ex) {
                if (ex.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    throw new IllegalArgumentException("The base DN " + request.getBaseDN() + " doesn't exist on "
                            + dc.getDisplayName() + ".");
                }
                throw ex;
            }
            for (SearchResultEntry e : result.getSearchEntries()) {
                Entry compact = compact(e, kept);
                tracker.bytes += estimateBytes(compact);
                out.add(compact);
            }
            if (tracker.bytes > memoryBudgetBytes) {
                throw new IllegalArgumentException("Reading side " + label + " (" + dc.getDisplayName()
                        + ") needs more memory than a comparison may use (about " + memoryBudgetBytes / (1024 * 1024)
                        + " MB, reached after " + out.size() + " entries). Narrow the base DN or the filter, compare "
                        + "only some attributes, or raise ldapportal.directory-diff.memory-budget-mb along with "
                        + "the server's heap.");
            }
            if (out.size() > maxEntriesPerSide) {
                throw new IllegalArgumentException(dc.getDisplayName() + " has more than " + maxEntriesPerSide
                        + " entries under " + request.getBaseDN() + ". Narrow the base DN or the filter.");
            }
            tracker.run.setProcessed(out.size());
            tracker.tick();
            SimplePagedResultsControl response = SimplePagedResultsControl.get(result);
            cookie = (response != null && response.moreResultsToReturn()) ? response.getCookie() : null;
        } while (cookie != null && cookie.getValueLength() > 0);
        return out;
    }

    /** Pair and compare, writing each result as it comes and keeping the counts. */
    private void compare(DirectoryDiffRun run, Settings settings, List<Entry> a, List<Entry> b,
                         com.unboundid.ldap.sdk.schema.Schema schema, Tracker tracker) {
        DirectoryDiffEngine.Options options = options(settings, schema);
        Map<String, int[]> attributeCounts = new LinkedHashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();
        List<DirectoryDiffEntryStore.Row> pending = new ArrayList<>();
        int[] seq = {0};
        Consumer<DirectoryDiffEngine.Result> sink = result -> {
            List<String> names = new ArrayList<>();
            for (DirectoryDiffEngine.AttributeDiff d : result.diffs()) {
                names.add(d.name());
                String key = d.name().toLowerCase(Locale.ROOT);
                displayNames.putIfAbsent(key, d.name());
                attributeCounts.computeIfAbsent(key, k -> new int[1])[0]++;
            }
            switch (result.category()) {
                case SAME -> run.setSameCount(run.getSameCount() + 1);
                case DIFFERENT -> run.setDifferentCount(run.getDifferentCount() + 1);
                case ONLY_A -> run.setOnlyACount(run.getOnlyACount() + 1);
                case ONLY_B -> run.setOnlyBCount(run.getOnlyBCount() + 1);
            }
            String detail = result.category() == DirectoryDiffCategory.DIFFERENT ? detailJson(result) : null;
            pending.add(new DirectoryDiffEntryStore.Row(++seq[0], result.category(), result.dnA(), result.dnB(),
                    names, result.note(), detail));
            run.setCurrentDn(result.dnA() != null ? result.dnA() : result.dnB());
            if (pending.size() >= WRITE_BATCH) {
                entryStore.append(run.getId(), List.copyOf(pending));
                pending.clear();
            }
        };
        DirectoryDiffEngine.compare(a, b, options, sink,
                n -> {
                    run.setProcessed(run.getProcessed() + n);
                    tracker.tick();
                },
                () -> false);
        entryStore.append(run.getId(), pending);
        List<DirectoryDiffRunResponse.AttributeCount> counts = new ArrayList<>();
        attributeCounts.forEach((key, n) -> counts.add(new DirectoryDiffRunResponse.AttributeCount(displayNames.get(key), n[0])));
        counts.sort(Comparator.comparingInt(DirectoryDiffRunResponse.AttributeCount::count).reversed()
                .thenComparing(DirectoryDiffRunResponse.AttributeCount::name));
        run.setAttributeCounts(toJson(counts));
        run.setProcessed(run.getTotal());
    }

    /** The engine's options for these settings: which attributes count, and how side A's DNs map onto side B. */
    DirectoryDiffEngine.Options options(Settings settings, com.unboundid.ldap.sdk.schema.Schema schema) {
        return new DirectoryDiffEngine.Options(settings.pairBy(), settings.pairAttribute(),
                DirectoryDiffEngine.compared(settings.attributes(), settings.ignore()), schema,
                DirectoryDiffEngine.rebase(settings.a().baseDn(), settings.b().baseDn()));
    }

    /**
     * Keep a pair of directories' history short: its latest {@code keep-per-pair}
     * complete runs, plus failed or cancelled runs only while they are among the
     * latest {@code keep-per-pair} overall — so a few failed attempts never push
     * out the last good result. A run still going is never touched.
     */
    void prune(UUID directoryAId, UUID directoryBId) {
        try {
            List<DirectoryDiffRun> runs = runRepo.findByDirectoryAIdAndDirectoryBIdOrderByStartedAtDesc(
                    directoryAId, directoryBId);
            int complete = 0;
            for (int i = 0; i < runs.size(); i++) {
                DirectoryDiffRun run = runs.get(i);
                boolean keep;
                if (run.getStatus() == DirectoryDiffStatus.RUNNING && !stale(run)) {
                    keep = true;
                } else if (run.getStatus() == DirectoryDiffStatus.COMPLETE) {
                    keep = ++complete <= keepPerPair;
                } else {
                    keep = i < keepPerPair;
                }
                if (!keep) {
                    entryStore.deleteRun(run.getId());
                    runRepo.delete(run);
                }
            }
        } catch (Exception ex) {
            log.warn("Directory diff: could not prune old runs: {}", ex.toString());
        }
    }

    // ── Reading results ──────────────────────────────────────────────────────────

    /** The latest runs, newest first. */
    public List<DirectoryDiffRunResponse> recent() {
        return runRepo.findTop20ByOrderByStartedAtDesc().stream().map(this::toResponse).toList();
    }

    public DirectoryDiffRunResponse get(UUID id) {
        return toResponse(require(id));
    }

    /** One page of a group: in run order, optionally only where {@code attribute} differs or the DN contains {@code q}. */
    public PageResponse<DirectoryDiffEntryResponse> entries(UUID id, DirectoryDiffCategory category, String attribute,
                                                            String q, int page, int size) {
        require(id);
        if (category == null) {
            throw new IllegalArgumentException("Choose a group: SAME, DIFFERENT, ONLY_A or ONLY_B.");
        }
        int p = Math.max(0, page);
        int n = Math.min(Math.max(1, size), 200);
        long total = entryStore.count(id, category, attribute, q);
        List<DirectoryDiffEntryResponse> content = entryStore.page(id, category, attribute, q, p * n, n).stream()
                .map(r -> new DirectoryDiffEntryResponse(r.seq(), r.category(), r.dnA(), r.dnB(), r.attributes(),
                        r.note()))
                .toList();
        return new PageResponse<>(content, total, (int) ((total + n - 1) / n), p, n);
    }

    /** One result as the run found it, with each differing attribute's values. */
    public DirectoryDiffEntryDetail entry(UUID id, int seq) {
        require(id);
        DirectoryDiffEntryStore.Row row = entryStore.get(id, seq)
                .orElseThrow(() -> new ResourceNotFoundException("No entry " + seq + " in this comparison"));
        StoredDetail detail = row.detail() == null ? new StoredDetail(List.of(), List.of())
                : fromJson(row.detail(), new TypeReference<StoredDetail>() { });
        return new DirectoryDiffEntryDetail(row.seq(), row.category(), row.dnA(), row.dnB(), detail.diffs(),
                detail.same(), row.note());
    }

    /** One result's entries read again now and compared the run's way, with the values of the equal attributes. */
    public DirectoryDiffLiveEntry live(UUID id, int seq) {
        DirectoryDiffRun run = require(id);
        DirectoryDiffEntryStore.Row row = entryStore.get(id, seq)
                .orElseThrow(() -> new ResourceNotFoundException("No entry " + seq + " in this comparison"));
        Settings settings = fromJson(run.getSettings(), new TypeReference<Settings>() { });
        DirectoryConnection dirA = directoryRepo.findById(run.getDirectoryAId())
                .orElseThrow(() -> new IllegalArgumentException("Side A's directory has been deleted."));
        DirectoryConnection dirB = directoryRepo.findById(run.getDirectoryBId())
                .orElseThrow(() -> new IllegalArgumentException("Side B's directory has been deleted."));
        String[] requested = requestedAttributes(settings);
        Entry a = row.dnA() == null ? null : readOne(dirA, row.dnA(), requested);
        Entry b = row.dnB() == null ? null : readOne(dirB, row.dnB(), requested);
        OffsetDateTime now = OffsetDateTime.now();
        if (a == null || b == null) {
            DirectoryDiffCategory category = a != null ? DirectoryDiffCategory.ONLY_A
                    : b != null ? DirectoryDiffCategory.ONLY_B : null;
            return new DirectoryDiffLiveEntry(category, row.dnA(), row.dnB(), a != null, b != null, now, List.of(),
                    List.of());
        }
        DirectoryDiffEngine.Result result = DirectoryDiffEngine.comparePair(a, b,
                options(settings, schemas.forDirectory(dirB)));
        return new DirectoryDiffLiveEntry(result.category(), row.dnA(), row.dnB(), true, true, now,
                result.diffs().stream().map(DirectoryDiffService::dto).toList(),
                result.same().stream().map(s -> new DirectoryDiffLiveEntry.SameAttribute(s.name(), s.values())).toList());
    }

    /** Hand each row of a group (every group when {@code null}) to {@code sink}, for a CSV export. */
    public void forEachEntry(UUID id, DirectoryDiffCategory category, Consumer<DirectoryDiffEntryResponse> sink) {
        require(id);
        entryStore.forEach(id, category, r -> sink.accept(new DirectoryDiffEntryResponse(r.seq(), r.category(),
                r.dnA(), r.dnB(), r.attributes(), r.note())));
    }

    private Entry readOne(DirectoryConnection dc, String dn, String[] attributes) {
        return connectionFactory.withConnection(dc, conn -> {
            try {
                return conn.getEntry(dn, attributes);
            } catch (LDAPException ex) {
                if (ex.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return null;
                }
                throw ex;
            }
        });
    }

    private static boolean sameDn(String x, String y) {
        try {
            return new DN(x).equals(new DN(y));
        } catch (LDAPException ex) {
            return x.equalsIgnoreCase(y);
        }
    }

    private DirectoryDiffRun require(UUID id) {
        return runRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Directory comparison not found: " + id));
    }

    // ── Mapping ──────────────────────────────────────────────────────────────────

    /** What a DIFFERENT row's detail holds. */
    record StoredDetail(List<DirectoryDiffAttributeDiff> diffs, List<String> same) {
    }

    private String detailJson(DirectoryDiffEngine.Result result) {
        return toJson(new StoredDetail(result.diffs().stream().map(DirectoryDiffService::dto).toList(),
                result.same().stream().map(DirectoryDiffEngine.SameAttribute::name).toList()));
    }

    private static DirectoryDiffAttributeDiff dto(DirectoryDiffEngine.AttributeDiff d) {
        return new DirectoryDiffAttributeDiff(d.name(), d.onlyA(), d.onlyACount(), d.onlyB(), d.onlyBCount(),
                d.both(), d.bothCount());
    }

    private static boolean stale(DirectoryDiffRun run) {
        return run.getHeartbeatAt().isBefore(OffsetDateTime.now().minus(STALE_AFTER));
    }

    DirectoryDiffRunResponse toResponse(DirectoryDiffRun run) {
        Settings settings = fromJson(run.getSettings(), new TypeReference<Settings>() { });
        DirectoryDiffStatus status = run.getStatus();
        String error = run.getError();
        if (status == DirectoryDiffStatus.RUNNING && stale(run)) {
            status = DirectoryDiffStatus.FAILED;
            error = INTERRUPTED;
        }
        boolean running = status == DirectoryDiffStatus.RUNNING;
        List<DirectoryDiffRunResponse.AttributeCount> attributeCounts = run.getAttributeCounts() == null ? List.of()
                : fromJson(run.getAttributeCounts(), new TypeReference<List<DirectoryDiffRunResponse.AttributeCount>>() { });
        return new DirectoryDiffRunResponse(run.getId(), status, running ? run.getPhase() : DirectoryDiffPhase.DONE,
                run.getProcessed(), run.getTotal(), running ? run.getCurrentDn() : null, error, run.getRequestedBy(),
                run.getStartedAt(), run.getHeartbeatAt(), run.getFinishedAt(),
                side(settings.a(), run.getDirectoryAName()), side(settings.b(), run.getDirectoryBName()),
                settings.pairBy(), settings.pairAttribute(), settings.attributes(), settings.ignore(),
                new DirectoryDiffRunResponse.Counts(run.getSameCount(), run.getDifferentCount(), run.getOnlyACount(),
                        run.getOnlyBCount()),
                attributeCounts);
    }

    private static DirectoryDiffRunResponse.Side side(Side s, String name) {
        return new DirectoryDiffRunResponse.Side(s.directoryId(), name, s.baseDn(), s.scope(), s.filter());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize a directory diff value", ex);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not read a stored directory diff value", ex);
        }
    }

    private static String message(Exception ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getMessage() == null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }
}
