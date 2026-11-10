// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.sync.MembershipResponse;
import com.ldapportal.dto.sync.SyncLinkRequest;
import com.ldapportal.dto.sync.SyncLinkResponse;
import com.ldapportal.dto.sync.SyncSetRequest;
import com.ldapportal.dto.sync.SyncSetResponse;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.SyncTransformRule;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SyncCaptureMode;
import com.ldapportal.entity.enums.SyncChangelogHealth;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.sync.MembershipReconciler;
import com.ldapportal.ldap.sync.RecomputeEngine;
import com.ldapportal.ldap.sync.RecomputeEnqueuer;
import com.ldapportal.ldap.sync.SyncAuditLabels;
import com.ldapportal.ldap.sync.SyncContentVerifier;
import com.ldapportal.ldap.sync.SyncPreviewService;
import com.ldapportal.ldap.sync.SyncReconcileHistory;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.MembershipStateCount;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Superadmin CRUD + operations for the sync engine's configuration: links, sync
 * sets, the membership inventory, and operator triggers (reconcile / recompute /
 * resolve a quarantine). Structural validation lives here (DN syntax, filter
 * syntax, distinct endpoints); throws {@link IllegalArgumentException} for
 * 400-class failures and {@link ResourceNotFoundException} for 404.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyncConfigService {

    /** LDAP attribute descriptor (a leading letter, then letters/digits/hyphens). */
    private static final Pattern ATTR_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9-]*$");

    private final SyncLinkRepository linkRepo;
    private final SyncSetRepository setRepo;
    private final MembershipRepository membershipRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final MembershipReconciler reconciler;
    private final SyncContentVerifier verifier;
    private final RecomputeEnqueuer enqueuer;
    private final SyncPreviewService previewService;
    private final SyncReconcileHistory reconcileHistory;
    private final RecomputeEngine engine;
    private final AuditService auditService;
    private final com.ldapportal.repository.SyncReconcileRunRepository reconcileRunRepo;
    private final com.ldapportal.ldap.sync.SyncVerifyProgressTracker verifyProgress;
    private final com.ldapportal.ldap.sync.SyncDriftStore driftStore;
    private final com.ldapportal.ldap.sync.SyncHeldStore heldStore;
    private final com.ldapportal.ldap.sync.SyncVerifySnapshot verifySnapshot;

    /**
     * Runs the reconciles {@link #startReconcile} starts, off the request thread:
     * a run can outlast any proxy's idle timeout. One per set at most (the
     * reconcile lease), so a cached pool stays small.
     */
    private final java.util.concurrent.ExecutorService manualReconciles =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "sync-manual-reconcile");
                t.setDaemon(true);
                return t;
            });

    @jakarta.annotation.PreDestroy
    void stopManualReconciles() {
        manualReconciles.shutdownNow();
    }

    // ── Links ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SyncLinkResponse> listLinks() {
        return linkRepo.findAll().stream().map(SyncLinkResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public SyncLinkResponse getLink(UUID id) {
        return SyncLinkResponse.of(requireLink(id));
    }

    @Transactional
    public SyncLinkResponse createLink(SyncLinkRequest req) {
        validateLink(req);
        SyncLink l = new SyncLink();
        applyLink(l, req);
        return SyncLinkResponse.of(linkRepo.save(l));
    }

    @Transactional
    public SyncLinkResponse updateLink(UUID id, SyncLinkRequest req) {
        validateLink(req);
        SyncLink l = requireLink(id);
        applyLink(l, req);
        return SyncLinkResponse.of(linkRepo.save(l));
    }

    @Transactional
    public void deleteLink(UUID id, AuthPrincipal principal) {
        SyncLink l = requireLink(id);
        if (!setRepo.findAllByLinkId(id).isEmpty()) {
            throw new IllegalArgumentException("Delete the link's sync sets before deleting the link");
        }
        linkRepo.delete(l);
        if (principal != null) {
            // Recorded against the target directory, as a set delete is. A
            // link has no sets by now, so all that goes is its configuration
            // and, for changelog capture, its position in the changelog.
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("linkId", id.toString());
            detail.put("linkName", l.getDisplayName());
            SyncAuditLabels.of(null, l, directoryRepo).putInto(detail);
            detail.put("sourceDirId", String.valueOf(l.getSourceDirId()));
            detail.put("targetDirId", String.valueOf(l.getTargetDirId()));
            detail.put("captureMode", String.valueOf(l.getCaptureMode()));
            if (l.getCaptureMode() == SyncCaptureMode.CHANGELOG) {
                detail.put("changelogFormat", String.valueOf(l.getChangelogFormat()));
                detail.put("changelogBaseDn", l.getChangelogBaseDn());
                if (l.getChangelogLastChangeNumber() != null) {
                    detail.put("changelogLastChangeNumber", l.getChangelogLastChangeNumber());
                }
            }
            auditService.record(principal, l.getTargetDirId(), AuditAction.SYNC_LINK_DELETE, null, detail);
        }
    }

    private void applyLink(SyncLink l, SyncLinkRequest req) {
        SyncCaptureMode mode = req.captureMode() != null ? req.captureMode() : SyncCaptureMode.APP_INTERCEPT;
        l.setDisplayName(req.displayName());
        l.setSourceDirId(req.sourceDirId());
        l.setTargetDirId(req.targetDirId());
        l.setEnabled(req.enabled());
        l.setCaptureMode(mode);
        if (mode == SyncCaptureMode.CHANGELOG) {
            String baseDn = req.changelogBaseDn().trim();
            // A cursor is only meaningful for the feed that issued it (a DSEE
            // changeNumber is not an OUD cookie), so a feed change restarts it,
            // along with the old feed's health and error.
            if (l.getChangelogFormat() != req.changelogFormat()
                    || !Objects.equals(l.getChangelogBaseDn(), baseDn)) {
                l.setChangelogCursorToken(null);
                l.setChangelogLastChangeNumber(null);
                l.setChangelogSourceLastChangeNumber(null);
                l.setChangelogHealth(SyncChangelogHealth.HEALTHY);
                l.setChangelogLastError(null);
                l.setChangelogLastErrorAt(null);
                l.setChangelogLastPolledAt(null);
            }
            l.setChangelogFormat(req.changelogFormat());
            l.setChangelogBaseDn(baseDn);
        } else {
            // APP_INTERCEPT leaves changelog config null (DB cfg constraint).
            l.setChangelogFormat(null);
            l.setChangelogBaseDn(null);
        }
    }

    private void validateLink(SyncLinkRequest req) {
        if (req.sourceDirId().equals(req.targetDirId())) {
            throw new IllegalArgumentException("Source and target directories must differ");
        }
        if (!directoryRepo.existsById(req.sourceDirId())) {
            throw new IllegalArgumentException("Source directory not found");
        }
        if (!directoryRepo.existsById(req.targetDirId())) {
            throw new IllegalArgumentException("Target directory not found");
        }
        if (req.captureMode() == SyncCaptureMode.CHANGELOG) {
            if (req.changelogFormat() == null) {
                throw new IllegalArgumentException("changelogFormat is required for CHANGELOG capture");
            }
            if (req.changelogFormat() != ChangelogFormat.DSEE_CHANGELOG
                    && req.changelogFormat() != ChangelogFormat.OUD_COOKIE_CHANGELOG) {
                throw new IllegalArgumentException("changelogFormat " + req.changelogFormat()
                        + " is not supported for CHANGELOG capture (use DSEE_CHANGELOG or OUD_COOKIE_CHANGELOG)");
            }
            if (req.changelogBaseDn() == null || req.changelogBaseDn().isBlank()) {
                throw new IllegalArgumentException("changelogBaseDn is required for CHANGELOG capture");
            }
        }
    }

    // ── Sync sets ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SyncSetResponse> listSets(UUID linkId) {
        List<SyncSet> sets = linkId != null ? setRepo.findAllByLinkId(linkId) : setRepo.findAll();
        Map<UUID, Map<String, Long>> counts = membershipStateCounts();
        return sets.stream()
                .map(s -> SyncSetResponse.of(s, counts.getOrDefault(s.getId(), Map.of())))
                .toList();
    }

    /**
     * Membership counts per set keyed by state name, for the health rollup, plus
     * {@value #HELD_DELETE_KEY}: how many of the {@code REVIEW} rows are held
     * deletes (a subset of {@code REVIEW}, not an extra state).
     */
    private Map<UUID, Map<String, Long>> membershipStateCounts() {
        Map<UUID, Map<String, Long>> counts = new HashMap<>();
        for (MembershipStateCount c : membershipRepo.countGroupedByState()) {
            counts.computeIfAbsent(c.getSyncSetId(), k -> new HashMap<>())
                    .put(c.getState().name(), c.getCnt());
        }
        for (Object[] row : membershipRepo.countHeldDeletesBySet(
                com.ldapportal.dto.sync.SyncReviewKind.PENDING_DELETE_PREFIX + "%")) {
            counts.computeIfAbsent((UUID) row[0], k -> new HashMap<>())
                    .put(HELD_DELETE_KEY, ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** {@code stateCounts} key for the held deletes among a set's {@code REVIEW} rows. */
    public static final String HELD_DELETE_KEY = "REVIEW_DELETE";

    @Transactional(readOnly = true)
    public SyncSetResponse getSet(UUID id) {
        return SyncSetResponse.of(requireSet(id));
    }

    @Transactional
    public SyncSetResponse createSet(SyncSetRequest req) {
        validateSet(req);
        SyncSet s = new SyncSet();
        applySet(s, req);
        return SyncSetResponse.of(setRepo.save(s));
    }

    @Transactional
    public SyncSetResponse updateSet(UUID id, SyncSetRequest req) {
        validateSet(req);
        SyncSet s = requireSet(id);
        applySet(s, req);
        return SyncSetResponse.of(setRepo.save(s));
    }

    @Transactional
    public void deleteSet(UUID id, AuthPrincipal principal) {
        SyncSet s = requireSet(id);
        com.ldapportal.dto.sync.SyncSetDeleteImpact impact = deleteImpact(s);
        // membership, recompute_request, reconcile-run and preview rows
        // cascade via FK ON DELETE CASCADE; the target entries are untouched.
        setRepo.delete(s);
        if (principal != null) {
            // Recorded against the target directory: that's where the set's
            // entries are left behind, now unmanaged.
            SyncLink link = linkRepo.findById(s.getLinkId()).orElse(null);
            UUID targetDirId = link != null ? link.getTargetDirId() : null;
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            SyncAuditLabels.of(s.getName(), link, directoryRepo).putInto(detail);
            detail.put("syncSetId", id.toString());
            detail.put("linkId", String.valueOf(s.getLinkId()));
            detail.put("managedEntries", impact.managed());
            detail.put("pendingReview", impact.pendingReview());
            detail.put("reconcileRuns", impact.reconcileRuns());
            auditService.record(principal, targetDirId, AuditAction.SYNC_SET_DELETE, s.getTargetBaseDn(), detail);
        }
    }

    /** What deleting the set would discard (for the confirmation before a delete). */
    public com.ldapportal.dto.sync.SyncSetDeleteImpact deleteImpact(UUID id) {
        return deleteImpact(requireSet(id));
    }

    private com.ldapportal.dto.sync.SyncSetDeleteImpact deleteImpact(SyncSet s) {
        List<String> otherSets = setRepo.findAll().stream()
                .filter(o -> !o.getId().equals(s.getId()))
                .map(SyncSet::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        return new com.ldapportal.dto.sync.SyncSetDeleteImpact(s.getName(),
                membershipRepo.countBySyncSetId(s.getId()),
                membershipRepo.countBySyncSetIdAndState(s.getId(), MembershipState.REVIEW),
                reconcileRunRepo.countBySyncSetId(s.getId()),
                otherSets);
    }

    private void applySet(SyncSet s, SyncSetRequest req) {
        s.setLinkId(req.linkId());
        s.setName(req.name());
        s.setObjectScopeBaseDn(blankToNull(req.objectScopeBaseDn()));
        s.setObjectScope(req.objectScope());
        s.setIdentityKey(blankToNull(req.identityKey()));
        s.setTargetBaseDn(blankToNull(req.targetBaseDn()));
        s.setApplicabilityFilter(normalizeFilter(req.applicabilityFilter()));
        s.setReferenceAttributes(blankToNull(req.referenceAttributes()));
        s.setSourceAnchorAttribute(blankToNull(req.sourceAnchorAttribute()));
        // Default to the safe REVIEW policy (quarantine deletes) when unspecified.
        s.setDeletePolicy(req.deletePolicy() != null ? req.deletePolicy() : SyncDeletePolicy.REVIEW);
        s.setTransformRules(normalizeTransformRules(req.transformRules()));
        s.setExcludedAttributes(normalizeExcludedAttributes(req.excludedAttributes()));
        s.setReconcileCadenceSeconds(req.reconcileCadenceSeconds());
        s.setEnabled(req.enabled());
        if (req.loggingLevel() != null) {
            s.setLoggingLevel(req.loggingLevel());
        }
    }

    /**
     * Trim/dedupe the excluded-attribute override. {@code null} is preserved
     * (engine defaults apply); a non-null list — including empty after cleaning
     * (operator chose to exclude nothing) — is kept. Case-insensitive dedupe,
     * preserving the operator's casing and order.
     */
    private static List<String> normalizeExcludedAttributes(List<String> names) {
        if (names == null) {
            return null;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> out = new java.util.ArrayList<>();
        for (String n : names) {
            if (n == null || n.isBlank()) {
                continue;
            }
            String trimmed = n.trim();
            if (seen.add(trimmed.toLowerCase(java.util.Locale.ROOT))) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private void validateSet(SyncSetRequest req) {
        if (!linkRepo.existsById(req.linkId())) {
            throw new IllegalArgumentException("Sync link not found");
        }
        if (req.objectScopeBaseDn() == null || req.objectScopeBaseDn().isBlank()) {
            throw new IllegalArgumentException("Source base DN is required.");
        }
        requireValidDn(req.objectScopeBaseDn(), "objectScopeBaseDn");
        requireValidDn(req.targetBaseDn(), "targetBaseDn");
        requireBasesWithinRoots(req);
        String filter = normalizeFilter(req.applicabilityFilter());
        if (filter != null) {
            try {
                Filter.create(filter);
            } catch (LDAPException e) {
                throw new IllegalArgumentException("Invalid applicabilityFilter: " + e.getMessage());
            }
        }
        validateTransformRules(req.transformRules());
    }

    /**
     * The source base DN must lie under one of the source directory's base DNs,
     * or the set would enumerate nothing.
     *
     * <p>A blank target base means "the same DNs as on the source": entries are
     * placed at their source DNs, under the source base. That needs the source
     * base to lie under one of the target directory's base DNs too; otherwise the
     * entries would have nowhere to go, so the target base must be set.
     */
    private void requireBasesWithinRoots(SyncSetRequest req) {
        String sourceBase = req.objectScopeBaseDn().trim();
        boolean noTarget = req.targetBaseDn() == null || req.targetBaseDn().isBlank();
        linkRepo.findById(req.linkId()).ifPresent(link -> {
            DirectoryConnection source = directory(link.getSourceDirId());
            if (source != null && !com.ldapportal.ldap.DirectoryRoots.isWithin(source, sourceBase)) {
                throw new IllegalArgumentException("The source base DN '" + sourceBase
                        + "' isn't under any of the source directory's base DNs.");
            }
            DirectoryConnection target = directory(link.getTargetDirId());
            if (noTarget && target != null && !com.ldapportal.ldap.DirectoryRoots.isWithin(target, sourceBase)) {
                throw new IllegalArgumentException("A blank target base DN places entries at their source "
                        + "DNs, but '" + sourceBase + "' isn't under any of the target directory's base DNs: "
                        + "set the target base DN.");
            }
        });
    }

    private DirectoryConnection directory(UUID directoryId) {
        return directoryId == null ? null : directoryRepo.findById(directoryId).orElse(null);
    }

    /**
     * Validate attribute-mapping rules to the engine's actual capability: a rule
     * keys on a non-blank {@code sourceAttr} (the engine matches first-wins on it,
     * so a duplicate would silently shadow), attribute names look like LDAP
     * descriptors, and {@code valueTemplate} carries no token other than the single
     * {@code ${value}} substitution the engine understands.
     */
    private void validateTransformRules(List<SyncTransformRule> rules) {
        if (rules == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (SyncTransformRule rule : rules) {
            String src = rule.getSourceAttr();
            if (src == null || src.isBlank()) {
                throw new IllegalArgumentException("Transform rule sourceAttr is required");
            }
            if (!ATTR_NAME.matcher(src.trim()).matches()) {
                throw new IllegalArgumentException("Invalid transform rule sourceAttr: " + src);
            }
            if (!seen.add(src.trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Duplicate transform rule for source attribute: " + src.trim());
            }
            String tgt = rule.getTargetAttr();
            if (tgt != null && !tgt.isBlank() && !ATTR_NAME.matcher(tgt.trim()).matches()) {
                throw new IllegalArgumentException("Invalid transform rule targetAttr: " + tgt);
            }
            String tpl = rule.getValueTemplate();
            if (tpl != null && tpl.replace("${value}", "").contains("${")) {
                throw new IllegalArgumentException(
                        "Invalid transform rule valueTemplate (only ${value} is supported): " + tpl);
            }
        }
    }

    /**
     * Trim and prune transform rules to a canonical form for storage: drop rows
     * with a blank {@code sourceAttr}, collapse blank target/template to null (the
     * engine reads null target as "same name" and null/blank template as
     * passthrough), and an empty list to null.
     */
    private static List<SyncTransformRule> normalizeTransformRules(List<SyncTransformRule> rules) {
        if (rules == null) {
            return null;
        }
        List<SyncTransformRule> out = new ArrayList<>();
        for (SyncTransformRule r : rules) {
            if (r.getSourceAttr() == null || r.getSourceAttr().isBlank()) {
                continue;
            }
            out.add(new SyncTransformRule(
                    r.getSourceAttr().trim(),
                    blankToNull(r.getTargetAttr()),
                    blankToNull(r.getValueTemplate())));
        }
        return out.isEmpty() ? null : out;
    }

    // ── Membership inventory + operator triggers ────────────────────────────────

    @Transactional(readOnly = true)
    public Page<MembershipResponse> listMemberships(UUID syncSetId, MembershipState state,
                                                    String q, Pageable pageable) {
        requireSet(syncSetId);
        String term = (q == null || q.isBlank()) ? null
                : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return membershipRepo.search(syncSetId, state, term, pageable).map(MembershipResponse::of);
    }

    /**
     * Start a reconcile of the set now, in the background, recorded in its
     * history as a manual run by {@code requestedBy}, and return at once: the
     * run's progress and outcome are on {@code GET …/activity} and in its run
     * history. Unlike {@link #reconcileNow}, nothing waits on it, so a long run
     * can't be cut off by a proxy's timeout.
     *
     * @throws IllegalArgumentException while the set or its link is disabled
     * @throws com.ldapportal.exception.ConflictException while a reconcile of the set is running
     */
    public void startReconcile(UUID syncSetId, String requestedBy) {
        SyncSet set = requireSet(syncSetId);
        if (!set.isEnabled() || !linkRepo.findById(set.getLinkId()).map(SyncLink::isEnabled).orElse(false)) {
            throw new IllegalArgumentException("Enable the sync set and its link to reconcile it.");
        }
        if (MembershipReconciler.isRunning(set, java.time.OffsetDateTime.now())) {
            throw new com.ldapportal.exception.ConflictException("A reconcile of this sync set is already running.");
        }
        manualReconciles.execute(() -> {
            try {
                reconcileNow(syncSetId, requestedBy);
            } catch (com.ldapportal.exception.ConflictException ex) {
                // Another started between the check and this run's claim: it covers the request.
                log.info("Sync set {}: manual reconcile by {} not started: {}", syncSetId, requestedBy,
                        ex.getMessage());
            } catch (Exception ex) {
                // The run records its own failure in the history; this is for the log.
                log.warn("Sync set {}: manual reconcile by {} failed: {}", syncSetId, requestedBy, ex.toString());
            }
        });
        log.info("Sync set {}: manual reconcile started by {}", syncSetId, requestedBy);
    }

    /**
     * Run a synchronous reconcile of the set, recorded in its history as a manual
     * run by {@code requestedBy}; returns the number of source identities enumerated.
     */
    public int reconcileNow(UUID syncSetId, String requestedBy) {
        requireSet(syncSetId);
        int enumerated = reconciler.reconcile(syncSetId, SyncReconcileTrigger.MANUAL, requestedBy);
        // Refresh the cached drift snapshot so the dashboard reflects the post-reconcile
        // state, mirroring the scheduled sweep (reconcile → re-verify). Guarded: the
        // reconcile already applied, so a verify hiccup must not fail the operation.
        try {
            refreshDriftSnapshot(syncSetId, true);
        } catch (Exception ex) {
            log.warn("Post-reconcile drift refresh for sync set {} failed: {}", syncSetId, ex.toString());
        }
        return enumerated;
    }

    /**
     * Ask for a reconcile of the set on the scheduler's next tick, without
     * waiting for it: e.g. after its filter, scope or placement changed, so the
     * new configuration is applied now rather than at its next scheduled run.
     * A reconcile already running finishes first and the requested one follows.
     *
     * @return whether it will run: false while the set or its link is disabled
     *         (it then runs once both are enabled)
     */
    public boolean requestReconcile(UUID syncSetId, String requestedBy) {
        SyncSet set = requireSet(syncSetId);
        setRepo.requestReconcileOfSet(syncSetId);
        log.info("Sync set {}: reconcile requested by {}", syncSetId, requestedBy);
        return set.isEnabled() && linkRepo.findById(set.getLinkId()).map(SyncLink::isEnabled).orElse(false);
    }

    /**
     * Re-check the set's target, e.g. after it was restored from a backup: forget
     * what the set last wrote for each applied entry, so the next reconcile reads
     * every target entry and re-applies the ones that no longer match, then ask
     * for that reconcile. Writes nothing to the directories itself. The
     * reconcile runs on the scheduler's next tick, under the set's reconcile
     * lease; a reconcile already running finishes first and the requested one
     * follows it.
     */
    public com.ldapportal.dto.sync.SyncRecheckResult recheckTarget(UUID syncSetId, String requestedBy) {
        SyncSet set = requireSet(syncSetId);
        int entries = membershipRepo.forgetAppliedContent(syncSetId, new byte[0]);
        setRepo.requestReconcileOfSet(syncSetId);
        log.info("Sync set {}: target re-check requested by {}; {} applied entries will be re-read",
                syncSetId, requestedBy, entries);
        // A reconcile only runs for an enabled set on an enabled link.
        boolean willRun = set.isEnabled()
                && linkRepo.findById(set.getLinkId()).map(SyncLink::isEnabled).orElse(false);
        return new com.ldapportal.dto.sync.SyncRecheckResult(entries, willRun);
    }

    /** Everything recorded about one reconcile run of the set (404 when it isn't one of the set's runs). */
    public com.ldapportal.dto.sync.SyncReconcileRunDetail reconcileRun(UUID syncSetId, UUID runId) {
        requireSet(syncSetId);
        return reconcileHistory.detail(syncSetId, runId);
    }

    /**
     * The set's most recent runs, newest first (at most {@code limit}):
     * reconciles, incremental passes, or both when {@code kind} is {@code null}.
     */
    public List<com.ldapportal.dto.sync.SyncReconcileRunResponse> reconcileRuns(
            UUID syncSetId, int limit, com.ldapportal.ldap.sync.SyncReconcileHistory.RunKind kind) {
        requireSet(syncSetId);
        return reconcileHistory.recent(syncSetId, limit, kind);
    }

    /**
     * What the set's inventory actions are doing: its latest reconcile run and
     * its latest content verification, each with live progress while it runs.
     */
    public com.ldapportal.dto.sync.SyncSetActivity activity(UUID syncSetId) {
        requireSet(syncSetId);
        var reconcile = reconcileHistory.recent(syncSetId, 1,
                com.ldapportal.ldap.sync.SyncReconcileHistory.RunKind.RECONCILE).stream().findFirst().orElse(null);
        return new com.ldapportal.dto.sync.SyncSetActivity(reconcile, verifyProgress.status(syncSetId),
                java.time.OffsetDateTime.now());
    }

    /**
     * The in-scope source entries the set doesn't manage, and why, as its
     * latest complete reconcile found them.
     */
    public com.ldapportal.dto.sync.SyncNotInSetPage notInSet(UUID syncSetId, String search, int page, int size) {
        requireSet(syncSetId);
        return reconcileHistory.notInSet(syncSetId, search, page, size);
    }

    /** Largest drift page. */
    static final int MAX_DRIFT_PAGE = 200;

    /**
     * One page of the drift items the set's last complete content verify found,
     * optionally one {@code category} and matching {@code q} (target DN, source
     * DN or a differing attribute's name or value).
     */
    @Transactional(readOnly = true)
    public com.ldapportal.dto.sync.SyncDriftPage drift(UUID syncSetId,
                                                     com.ldapportal.entity.enums.SyncDriftCategory category,
                                                     String q, int page, int size) {
        SyncSet set = requireSet(syncSetId);
        int pageSize = Math.max(1, Math.min(MAX_DRIFT_PAGE, size));
        int pageNo = Math.max(0, page);
        var counts = driftStore.countsByCategory(syncSetId);
        long stored = counts.values().stream().mapToLong(Long::longValue).sum();
        long recordedDrift = nz(set.getVerifyMissingCount()) + nz(set.getVerifyOrphanCount())
                + nz(set.getVerifyMismatchCount());
        // Counts from a verify before items were kept, with nothing stored yet.
        boolean detailsRecorded = set.getLastVerifiedAt() != null && (stored > 0 || recordedDrift == 0);
        long matched = driftStore.count(syncSetId, category, q);
        var entries = driftStore.page(syncSetId, category, q, pageNo * pageSize, pageSize).stream()
                .map(r -> new com.ldapportal.dto.sync.SyncDriftPage.Entry(r.seq(), r.item().category(),
                        r.item().targetDn(), r.item().sourceDn(), r.item().identity(), r.item().attributes()))
                .toList();
        return new com.ldapportal.dto.sync.SyncDriftPage(set.getLastVerifiedAt(), set.getVerifyIncompleteAt(),
                detailsRecorded, counts, matched, pageNo, pageSize, entries, heldStore.countsByStatus(syncSetId));
    }

    /**
     * Every held reference the set's last complete content verify found (only
     * the latest is kept), a page at a time: optionally one {@code status} and
     * matching {@code q}. Not drift: the target keeps its own value of each.
     */
    public com.ldapportal.dto.sync.SyncHeldPage heldReferences(UUID syncSetId,
                                                             com.ldapportal.entity.enums.SyncHeldStatus status,
                                                             String q, int page, int size) {
        SyncSet set = requireSet(syncSetId);
        int pageSize = Math.max(1, Math.min(MAX_DRIFT_PAGE, size));
        int pageNo = Math.max(0, page);
        var counts = heldStore.countsByStatus(syncSetId);
        long stored = counts.values().stream().mapToLong(Long::longValue).sum();
        // A held count from a verify made before every reference was kept, with nothing stored yet.
        boolean detailsRecorded = set.getLastVerifiedAt() != null && (stored > 0 || nz(set.getVerifyHeldCount()) == 0);
        long matched = heldStore.count(syncSetId, status, q);
        var entries = heldStore.page(syncSetId, status, q, pageNo * pageSize, pageSize).stream()
                .map(r -> new com.ldapportal.dto.sync.SyncHeldPage.Entry(r.seq(), r.ref().entryDn(),
                        r.ref().sourceDn(), r.ref().identity(), r.ref().attribute(), r.ref().reference(),
                        r.ref().status()))
                .toList();
        return new com.ldapportal.dto.sync.SyncHeldPage(set.getLastVerifiedAt(), detailsRecorded, counts, matched,
                pageNo, pageSize, entries);
    }

    /**
     * Every stored held reference as CSV
     * ({@code status,sourceDn,attribute,reference,entryDn,identity}), one row per
     * held value, in verify order: filter on {@code status = DANGLING} for the
     * references to fix on the source. Cells are neutralised as in
     * {@link #writeDriftCsv}.
     */
    public void writeHeldCsv(UUID syncSetId, java.io.Writer out) {
        requireSet(syncSetId);
        List<String> headers = List.of("status", "sourceDn", "attribute", "reference", "entryDn", "identity");
        try {
            com.ldapportal.util.CsvUtils.writeHeader(out, headers);
            heldStore.forEach(syncSetId, r -> {
                var ref = r.ref();
                Map<String, String> row = new java.util.HashMap<>();
                row.put("status", ref.status().name());
                row.put("sourceDn", safeCell(ref.sourceDn()));
                row.put("attribute", safeCell(ref.attribute()));
                row.put("reference", safeCell(ref.reference()));
                row.put("entryDn", safeCell(ref.entryDn()));
                row.put("identity", safeCell(ref.identity()));
                try {
                    com.ldapportal.util.CsvUtils.writeRow(out, headers, row);
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
            out.flush();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static long nz(Integer n) {
        return n == null ? 0 : n;
    }

    /**
     * Every stored drift item as CSV
     * ({@code category,targetDn,sourceDn,identity,attribute,expected,actual}): a
     * content drift gives one row per differing attribute, with its values
     * {@code |}-joined; secret attributes show {@code (hidden)}. A cell starting
     * with a formula character is neutralised, since values come from the directory.
     */
    public void writeDriftCsv(UUID syncSetId, java.io.Writer out) {
        requireSet(syncSetId);
        List<String> headers = List.of("category", "targetDn", "sourceDn", "identity", "attribute", "expected", "actual");
        try {
            com.ldapportal.util.CsvUtils.writeHeader(out, headers);
            driftStore.forEach(syncSetId, r -> {
                var it = r.item();
                if (it.attributes().isEmpty()) {
                    writeDriftRow(out, headers, it, null, "", "");
                } else {
                    for (var a : it.attributes()) {
                        writeDriftRow(out, headers, it, a.attribute(),
                                a.hidden() ? "(hidden)" : com.ldapportal.util.CsvUtils.joinMultiValue(a.expected()),
                                a.hidden() ? "(hidden)" : com.ldapportal.util.CsvUtils.joinMultiValue(a.actual()));
                    }
                }
            });
            out.flush();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static void writeDriftRow(java.io.Writer out, List<String> headers,
                                      com.ldapportal.dto.sync.SyncDriftItem it, String attribute,
                                      String expected, String actual) {
        Map<String, String> row = new java.util.HashMap<>();
        row.put("category", it.category().name());
        row.put("targetDn", safeCell(it.targetDn()));
        row.put("sourceDn", safeCell(it.sourceDn()));
        row.put("identity", safeCell(it.identity()));
        row.put("attribute", safeCell(attribute));
        row.put("expected", safeCell(expected));
        row.put("actual", safeCell(actual));
        try {
            com.ldapportal.util.CsvUtils.writeRow(out, headers, row);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /** A cell a spreadsheet won't evaluate: a leading formula character gets a quote. */
    static String safeCell(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        char c = v.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r' ? "'" + v : v;
    }

    // ── Drift repair ─────────────────────────────────────────────────────────

    /** Most drift items one repair (or its preview) takes: one page of the Drift tab. */
    static final int MAX_DRIFT_REPAIR = MAX_DRIFT_PAGE;

    /** One stored drift item and what its live re-check decided. */
    private record PlannedRepair(com.ldapportal.ldap.sync.SyncDriftStore.Row row,
                                 com.ldapportal.dto.sync.SyncDriftRepairPreview.Action action,
                                 com.ldapportal.ldap.sync.SyncContentVerifier.Recheck recheck, String reason) {
    }

    /**
     * What repairing these drift items would do, each re-checked live against
     * both directories now (the stored verify may be hours old): CREATE a
     * missing entry, REAPPLY a drifted one, or SKIP it and why. Never writes.
     */
    public com.ldapportal.dto.sync.SyncDriftRepairPreview previewDriftRepair(UUID syncSetId, List<Integer> seqs,
                                                                           java.time.OffsetDateTime verifiedAt) {
        requireCurrentVerify(requireSet(syncSetId), verifiedAt);
        return new com.ldapportal.dto.sync.SyncDriftRepairPreview(planRepair(syncSetId, seqs).stream()
                .map(p -> new com.ldapportal.dto.sync.SyncDriftRepairPreview.Item(p.row().seq(), p.action(),
                        p.recheck() != null && p.recheck().targetDn() != null ? p.recheck().targetDn()
                                : p.row().item().targetDn(),
                        p.row().item().sourceDn(),
                        p.recheck() != null ? p.recheck().attributes() : List.of(), p.reason()))
                .toList());
    }

    /**
     * Repair these drift items: re-check each live, then for each one still
     * missing or drifted forget what the set last wrote for it and recompute
     * it now, through the sync engine, so it's created or brought back to the
     * projected source exactly as a reconcile would (a placement conflict is
     * held for review, as ever). Each write is audited with the operator as
     * actor ({@code operation: drift-repair}). Items found fixed are dropped
     * from the stored verify, and the set's drift counts restated from what's left.
     */
    public com.ldapportal.dto.sync.SyncDriftRepairResult repairDrift(UUID syncSetId, List<Integer> seqs,
                                                                   java.time.OffsetDateTime verifiedAt,
                                                                   AuthPrincipal principal) {
        SyncSet set = requireSet(syncSetId);
        requireCurrentVerify(set, verifiedAt);
        List<PlannedRepair> plan = planRepair(syncSetId, seqs);
        var toRun = plan.stream().filter(p -> p.action() != com.ldapportal.dto.sync.SyncDriftRepairPreview.Action.SKIP)
                .toList();
        UUID targetDirectoryId = linkRepo.findById(set.getLinkId()).map(SyncLink::getTargetDirId).orElse(null);
        SyncAuditLabels labels = toRun.isEmpty() ? null : SyncAuditLabels.of(set, linkRepo, directoryRepo);

        List<com.ldapportal.dto.sync.SyncDriftRepairResult.Item> items = new ArrayList<>();
        List<com.ldapportal.ldap.sync.SyncDriftStore.Row> fixed = new ArrayList<>();
        int created = 0, reapplied = 0, inSync = 0, skipped = 0, held = 0, failed = 0;
        for (PlannedRepair p : plan) {
            int seq = p.row().seq();
            if (p.action() == com.ldapportal.dto.sync.SyncDriftRepairPreview.Action.SKIP) {
                skipped++;
                items.add(new com.ldapportal.dto.sync.SyncDriftRepairResult.Item(seq,
                        com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome.SKIPPED, p.row().item().targetDn(), p.reason()));
                if (p.recheck() != null && p.recheck().state()
                        == com.ldapportal.ldap.sync.SyncContentVerifier.RecheckState.IN_SYNC) {
                    fixed.add(p.row());
                }
                continue;
            }
            com.ldapportal.ldap.sync.RecomputeOutcome outcome;
            try {
                if (p.recheck().identity() != null) {
                    // Past the hash gate, just before this entry's recompute (so a
                    // repair cut short leaves the rest as they were): an unchanged
                    // projection must still re-read the target.
                    membershipRepo.forgetAppliedContentOf(syncSetId, List.of(p.recheck().identity()), new byte[0]);
                }
                outcome = engine.process(syncSetId, p.row().item().sourceDn(), false);
            } catch (RuntimeException ex) {
                log.warn("Sync set {}: drift repair of [{}] failed: {}", syncSetId, p.row().item().sourceDn(), ex.toString());
                failed++;
                items.add(new com.ldapportal.dto.sync.SyncDriftRepairResult.Item(seq,
                        com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome.FAILED, p.recheck().targetDn(),
                        "Couldn't recompute the entry: " + ex.getMessage()));
                continue;
            }
            if (outcome == null) {
                failed++;
                items.add(new com.ldapportal.dto.sync.SyncDriftRepairResult.Item(seq,
                        com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome.FAILED, p.recheck().targetDn(),
                        "The sync set or its link is disabled, or a directory is unavailable."));
                continue;
            }
            String dn = outcome.targetDn() != null ? outcome.targetDn() : p.recheck().targetDn();
            com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome o;
            String message = outcome.reason();
            switch (outcome.action()) {
                case ADD -> { o = com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome.CREATED; created++; }
                case MODIFY, MOVE -> { o = com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome.REAPPLIED; reapplied++; }
                case NO_CHANGE, UNCHANGED -> {
                    o = com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome.ALREADY_IN_SYNC;
                    inSync++;
                }
                case REVIEW -> { o = com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome.HELD; held++; }
                default -> {
                    o = com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome.FAILED;
                    failed++;
                    if (message == null) message = "The sync didn't apply it (" + outcome.action() + ").";
                }
            }
            items.add(new com.ldapportal.dto.sync.SyncDriftRepairResult.Item(seq, o, dn, message));
            if (o == com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome.CREATED
                    || o == com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome.REAPPLIED
                    || o == com.ldapportal.dto.sync.SyncDriftRepairResult.Outcome.ALREADY_IN_SYNC) {
                fixed.add(p.row());
            }
            AuditAction action = com.ldapportal.ldap.sync.SyncWriteAuditor.actionFor(outcome.action());
            if (action != null && principal != null && targetDirectoryId != null && dn != null) {
                Map<String, Object> detail = new java.util.LinkedHashMap<>();
                detail.put("source", "directory-sync");
                detail.put("operation", "drift-repair");
                labels.putInto(detail);
                detail.put("syncSetId", syncSetId.toString());
                if (p.recheck().identity() != null) detail.put("identity", p.recheck().identity());
                if (!outcome.attributes().isEmpty()) {
                    // Names only, never values, as the engine's own write audit.
                    detail.put("attributes", outcome.attributes());
                }
                auditService.record(principal, targetDirectoryId, action, dn, detail);
            }
        }
        if (!fixed.isEmpty()) {
            driftStore.delete(syncSetId, fixed);
            restateFromStore(syncSetId, set.getLastVerifiedAt());
        }
        log.info("Sync set {}: drift repair by {}: {} created, {} re-applied, {} already in sync, {} skipped, "
                        + "{} held, {} failed", syncSetId, principal != null ? principal.username() : "system",
                created, reapplied, inSync, skipped, held, failed);
        return new com.ldapportal.dto.sync.SyncDriftRepairResult(created, reapplied, inSync, skipped, held, failed,
                items);
    }

    // ── Orphan delete ────────────────────────────────────────────────────────

    /**
     * What deleting these orphaned drift items would do, each re-checked live
     * ({@code RecomputeEngine.orphanDelete} without applying): DELETE an entry
     * provably this set's, or SKIP it and why. Never writes.
     */
    public com.ldapportal.dto.sync.SyncOrphanDeletePreview previewOrphanDelete(UUID syncSetId, List<Integer> seqs,
                                                                             java.time.OffsetDateTime verifiedAt) {
        requireCurrentVerify(requireSet(syncSetId), verifiedAt);
        List<com.ldapportal.dto.sync.SyncOrphanDeletePreview.Item> items = new ArrayList<>();
        for (var row : storedRows(syncSetId, seqs)) {
            String dn = row.item().targetDn();
            if (row.item().category() != com.ldapportal.entity.enums.SyncDriftCategory.ORPHAN) {
                items.add(new com.ldapportal.dto.sync.SyncOrphanDeletePreview.Item(row.seq(),
                        com.ldapportal.dto.sync.SyncOrphanDeletePreview.Action.SKIP, dn,
                        "Only orphaned entries are deleted here; repair this one instead."));
                continue;
            }
            var check = orphanCheck(syncSetId, dn, false);
            items.add(new com.ldapportal.dto.sync.SyncOrphanDeletePreview.Item(row.seq(),
                    check.outcome() == com.ldapportal.dto.sync.SyncPreviewAction.DELETE
                            ? com.ldapportal.dto.sync.SyncOrphanDeletePreview.Action.DELETE
                            : com.ldapportal.dto.sync.SyncOrphanDeletePreview.Action.SKIP,
                    dn, check.message()));
        }
        return new com.ldapportal.dto.sync.SyncOrphanDeletePreview(items);
    }

    /**
     * Delete these orphaned drift items from the target, each re-checked live
     * first and left alone unless it's provably this set's (see
     * {@code RecomputeEngine.orphanDelete}). Needs {@code confirm} to be
     * {@value com.ldapportal.dto.sync.SyncOrphanDeleteRequest#CONFIRM_WORD}.
     * Each delete is audited with the operator ({@code entry.delete},
     * {@code operation: orphan-delete}). Deleted items, and ones already gone,
     * leave the stored verify, and the set's counts are restated.
     */
    public com.ldapportal.dto.sync.SyncOrphanDeleteResult deleteOrphans(UUID syncSetId, List<Integer> seqs,
                                                                      java.time.OffsetDateTime verifiedAt,
                                                                      String confirm, AuthPrincipal principal) {
        SyncSet set = requireSet(syncSetId);
        if (!com.ldapportal.dto.sync.SyncOrphanDeleteRequest.CONFIRM_WORD.equals(confirm)) {
            throw new IllegalArgumentException("Type " + com.ldapportal.dto.sync.SyncOrphanDeleteRequest.CONFIRM_WORD
                    + " to confirm deleting entries from the target.");
        }
        requireCurrentVerify(set, verifiedAt);
        var rows = storedRows(syncSetId, seqs);
        SyncAuditLabels labels = SyncAuditLabels.of(set, linkRepo, directoryRepo);
        List<com.ldapportal.dto.sync.SyncOrphanDeleteResult.Item> items = new ArrayList<>();
        List<com.ldapportal.ldap.sync.SyncDriftStore.Row> gone = new ArrayList<>();
        int deleted = 0, skipped = 0, failed = 0;
        for (var row : rows) {
            String dn = row.item().targetDn();
            if (row.item().category() != com.ldapportal.entity.enums.SyncDriftCategory.ORPHAN) {
                skipped++;
                items.add(new com.ldapportal.dto.sync.SyncOrphanDeleteResult.Item(row.seq(),
                        com.ldapportal.dto.sync.SyncOrphanDeleteResult.Outcome.SKIPPED, dn,
                        "Only orphaned entries are deleted here; repair this one instead."));
                continue;
            }
            var r = orphanCheck(syncSetId, dn, true);
            switch (r.outcome()) {
                case DELETE -> {
                    deleted++;
                    gone.add(row);
                    items.add(new com.ldapportal.dto.sync.SyncOrphanDeleteResult.Item(row.seq(),
                            com.ldapportal.dto.sync.SyncOrphanDeleteResult.Outcome.DELETED, dn, null));
                    if (principal != null && r.targetDirectoryId() != null) {
                        Map<String, Object> detail = new java.util.LinkedHashMap<>();
                        detail.put("source", "directory-sync");
                        detail.put("operation", "orphan-delete");
                        labels.putInto(detail);
                        detail.put("syncSetId", syncSetId.toString());
                        auditService.record(principal, r.targetDirectoryId(), AuditAction.ENTRY_DELETE, dn, detail);
                    }
                }
                case ERROR -> {
                    failed++;
                    items.add(new com.ldapportal.dto.sync.SyncOrphanDeleteResult.Item(row.seq(),
                            com.ldapportal.dto.sync.SyncOrphanDeleteResult.Outcome.FAILED, dn, r.message()));
                }
                default -> {
                    skipped++;
                    items.add(new com.ldapportal.dto.sync.SyncOrphanDeleteResult.Item(row.seq(),
                            com.ldapportal.dto.sync.SyncOrphanDeleteResult.Outcome.SKIPPED, dn, r.message()));
                    if (r.alreadyGone()) {
                        gone.add(row);
                    }
                }
            }
        }
        if (!gone.isEmpty()) {
            driftStore.delete(syncSetId, gone);
            restateFromStore(syncSetId, set.getLastVerifiedAt());
        }
        log.info("Sync set {}: orphan delete by {}: {} deleted, {} skipped, {} failed", syncSetId,
                principal != null ? principal.username() : "system", deleted, skipped, failed);
        return new com.ldapportal.dto.sync.SyncOrphanDeleteResult(deleted, skipped, failed, items);
    }


    private com.ldapportal.dto.sync.SyncDeleteApproval orphanCheck(UUID syncSetId, String dn, boolean apply) {
        try {
            return engine.orphanDelete(syncSetId, dn, apply);
        } catch (RuntimeException ex) {
            log.warn("Sync set {}: orphan {} of [{}] failed: {}", syncSetId, apply ? "delete" : "check", dn, ex.toString());
            String msg = ex.getMessage();
            return new com.ldapportal.dto.sync.SyncDeleteApproval(com.ldapportal.dto.sync.SyncPreviewAction.ERROR, null, dn,
                    "Couldn't check the entry: " + (msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : msg));
        }
    }

    /**
     * The stored rows for a repair or delete selection: at least one item, at
     * most {@value #MAX_DRIFT_REPAIR}, and still stored.
     */
    private List<com.ldapportal.ldap.sync.SyncDriftStore.Row> storedRows(UUID syncSetId, List<Integer> seqs) {
        List<Integer> wanted = seqs == null ? List.of() : seqs.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one drift item.");
        }
        if (wanted.size() > MAX_DRIFT_REPAIR) {
            throw new IllegalArgumentException("At most " + MAX_DRIFT_REPAIR + " drift items can be handled at once.");
        }
        var rows = driftStore.bySeq(syncSetId, wanted);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Those drift items are no longer stored; verify again and retry.");
        }
        return rows;
    }

    /**
     * Refuse a selection made from an older verify than the stored one. Item
     * positions are renumbered by every complete verify, so without this a
     * verify landing between loading the Drift tab (or its preview) and acting
     * would repair or delete entries the operator never chose.
     */
    private static void requireCurrentVerify(SyncSet set, java.time.OffsetDateTime verifiedAt) {
        java.time.OffsetDateTime stored = set.getLastVerifiedAt();
        if (verifiedAt == null || stored == null || !stored.isEqual(verifiedAt)) {
            throw new IllegalArgumentException("The drift list changed since you loaded it (a newer content check ran);"
                    + " reload it and choose again.");
        }
    }

    /**
     * Restate the set's drift counts from the items still stored, unless a
     * newer verify than the one at {@code verifiedAt} has replaced them meanwhile.
     */
    private void restateFromStore(UUID syncSetId, java.time.OffsetDateTime verifiedAt) {
        var left = driftStore.countsByCategory(syncSetId);
        setRepo.restateVerifyCounts(syncSetId, verifiedAt,
                left.getOrDefault(com.ldapportal.entity.enums.SyncDriftCategory.MISSING, 0L).intValue(),
                left.getOrDefault(com.ldapportal.entity.enums.SyncDriftCategory.ORPHAN, 0L).intValue(),
                left.getOrDefault(com.ldapportal.entity.enums.SyncDriftCategory.MISMATCH, 0L).intValue());
    }

    private List<PlannedRepair> planRepair(UUID syncSetId, List<Integer> seqs) {
        List<com.ldapportal.ldap.sync.SyncDriftStore.Row> rows = storedRows(syncSetId, seqs);
        var repairable = rows.stream().filter(r -> r.item().category() != com.ldapportal.entity.enums.SyncDriftCategory.ORPHAN
                && r.item().sourceDn() != null).toList();
        var rechecks = verifier.recheck(syncSetId, repairable.stream().map(r -> r.item().sourceDn()).toList());
        Map<Integer, com.ldapportal.ldap.sync.SyncContentVerifier.Recheck> bySeq = new java.util.HashMap<>();
        for (int i = 0; i < repairable.size(); i++) {
            bySeq.put(repairable.get(i).seq(), rechecks.get(i));
        }
        List<PlannedRepair> out = new ArrayList<>(rows.size());
        for (var row : rows) {
            var skip = com.ldapportal.dto.sync.SyncDriftRepairPreview.Action.SKIP;
            if (row.item().category() == com.ldapportal.entity.enums.SyncDriftCategory.ORPHAN) {
                out.add(new PlannedRepair(row, skip, null,
                        "An orphaned entry has no source member to repair it from."));
                continue;
            }
            var rc = bySeq.get(row.seq());
            if (rc == null) {
                out.add(new PlannedRepair(row, skip, null, "No source member was recorded for it."));
                continue;
            }
            switch (rc.state()) {
                case MISSING -> out.add(new PlannedRepair(row,
                        com.ldapportal.dto.sync.SyncDriftRepairPreview.Action.CREATE, rc, null));
                case MISMATCH -> out.add(new PlannedRepair(row,
                        com.ldapportal.dto.sync.SyncDriftRepairPreview.Action.REAPPLY, rc, null));
                case IN_SYNC -> out.add(new PlannedRepair(row, skip, rc, "It no longer drifts."));
                default -> out.add(new PlannedRepair(row, skip, rc, rc.note()));
            }
        }
        return out;
    }

    /** Check a run's entries can be downloaded (see {@link #writeRunEntriesCsv}), before streaming them. */
    public void requireRunEntries(UUID syncSetId, UUID runId) {
        requireSet(syncSetId);
        reconcileHistory.requireAllDnsRecorded(syncSetId, runId);
    }

    /**
     * Every entry a run changed or flagged, as CSV ({@code outcome,dn,reason}),
     * in processing order. Only for a run whose set was recording all DNs.
     */
    public void writeRunEntriesCsv(UUID syncSetId, UUID runId, java.io.Writer out) {
        requireSet(syncSetId);
        List<String> headers = List.of("outcome", "dn", "reason");
        try {
            com.ldapportal.util.CsvUtils.writeHeader(out, headers);
            reconcileHistory.entries(syncSetId, runId, e -> {
                Map<String, String> row = new java.util.HashMap<>();
                row.put("outcome", e.outcome());
                row.put("dn", e.targetDn());
                row.put("reason", e.reason() != null ? e.reason() : "");
                try {
                    com.ldapportal.util.CsvUtils.writeRow(out, headers, row);
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            });
            out.flush();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /** Dry-run preview of what a reconcile would change (no target writes). */
    public com.ldapportal.dto.sync.SyncReconcilePreview previewReconcile(UUID syncSetId) {
        requireSet(syncSetId);
        return reconciler.preview(syncSetId);
    }

    /** The set's latest per-entry dry-run preview: progress, or the summary once complete. */
    public com.ldapportal.dto.sync.SyncPreviewStatus previewStatus(UUID syncSetId) {
        requireSet(syncSetId);
        return previewService.status(syncSetId);
    }

    /** Start a per-entry dry-run preview in the background (joins one already running). */
    public com.ldapportal.dto.sync.SyncPreviewStatus startPreview(UUID syncSetId, String requestedBy) {
        requireSet(syncSetId);
        return previewService.start(syncSetId, requestedBy);
    }

    /** One page of the set's completed per-entry preview (what a reconcile would do to each entry). */
    public com.ldapportal.dto.sync.SyncPreviewPage previewEntries(UUID syncSetId, int page, int size,
                                                                 boolean changesOnly) {
        requireSet(syncSetId);
        return previewService.page(syncSetId, page, size, changesOnly);
    }

    /**
     * Independent content verification: compares the live source scope against the
     * live target base and flags missing / orphaned / drifted entries, without
     * consulting the membership index. Read-only to the directories; caches the
     * drift snapshot for the dashboard.
     */
    public com.ldapportal.dto.sync.SyncVerifyResult verifyContents(UUID syncSetId) {
        requireSet(syncSetId);
        return refreshDriftSnapshot(syncSetId, false);
    }

    /**
     * Re-read both directories and return the live drift result, caching it on the
     * set for the dashboard. Only a complete scan is persisted — a partial
     * enumeration would record misleadingly low counts over a previously good
     * snapshot. Shared by the verify endpoint and the post-reconcile refresh.
     *
     * @param afterReconcile also record the result on the reconcile run it follows
     */
    private com.ldapportal.dto.sync.SyncVerifyResult refreshDriftSnapshot(UUID syncSetId, boolean afterReconcile) {
        com.ldapportal.dto.sync.SyncVerifyResult result;
        try {
            result = verifier.verify(syncSetId);
        } catch (RuntimeException ex) {
            // It ran but failed: the stored snapshot stays, flagged as possibly stale.
            setRepo.recordVerifyIncomplete(syncSetId, java.time.OffsetDateTime.now());
            throw ex;
        }
        if (result.sourceComplete() && result.targetComplete()) {
            // Counts, held references and every item (for the Drift tab), together.
            verifySnapshot.record(syncSetId, result);
            if (afterReconcile) {
                reconcileHistory.recordDrift(syncSetId,
                        result.missingOnTarget(), result.orphanOnTarget(), result.contentMismatches());
            }
        } else if (result.note() == null) {
            // It ran but couldn't read a side completely (an unavailable set
            // carries a note instead): keep the snapshot, flag it as possibly stale.
            setRepo.recordVerifyIncomplete(syncSetId, java.time.OffsetDateTime.now());
        }
        return result;
    }

    /** Enqueue a recompute for a key (a source DN or a normalized identity). */
    @Transactional
    public void recompute(UUID syncSetId, String key) {
        requireSet(syncSetId);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key (source DN or identity) is required");
        }
        enqueuer.enqueue(syncSetId, key.trim(), null);
    }

    /**
     * Approve a delete held for review: delete the identity's target entry now,
     * regardless of the set's delete policy or the delete guard. Audited as an
     * {@code ENTRY_DELETE} on the target directory when the entry is removed.
     */
    public com.ldapportal.dto.sync.SyncDeleteApproval approveDelete(UUID syncSetId, String identity,
                                                                    AuthPrincipal principal) {
        SyncSet set = requireSet(syncSetId);
        com.ldapportal.dto.sync.SyncDeleteApproval result = engine.approveDelete(syncSetId, identity);
        if (result.outcome() == com.ldapportal.dto.sync.SyncPreviewAction.DELETE && principal != null) {
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("source", "directory-sync");
            detail.put("operation", "approved-review-delete");
            SyncAuditLabels.of(set, linkRepo, directoryRepo).putInto(detail);
            detail.put("syncSetId", syncSetId.toString());
            detail.put("identity", identity);
            auditService.record(principal, result.targetDirectoryId(), AuditAction.ENTRY_DELETE,
                    result.targetDn(), detail);
        }
        return result;
    }

    /** Dismiss a quarantined (or any) membership row — drop the index entry without touching the target. */
    @Transactional
    public void dismissMembership(UUID syncSetId, String identity) {
        requireSet(syncSetId);
        membershipRepo.findById(new com.ldapportal.entity.MembershipId(syncSetId, identity))
                .orElseThrow(() -> new ResourceNotFoundException("Membership not found"));
        membershipRepo.deleteById(new com.ldapportal.entity.MembershipId(syncSetId, identity));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SyncLink requireLink(UUID id) {
        return linkRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sync link not found"));
    }

    private SyncSet requireSet(UUID id) {
        return setRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("Sync set not found"));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * The applicability filter as the engine evaluates it. The editor lets a
     * long filter be laid out over several lines, but an LDAP filter allows no
     * whitespace between its components, so each line break and the whitespace
     * around it is removed (whitespace within a line, such as in a value, is
     * kept). Blank → {@code null} (every entry in scope).
     */
    static String normalizeFilter(String filter) {
        if (filter == null) {
            return null;
        }
        String joined = filter.replaceAll("\\s*\\R\\s*", "").trim();
        return joined.isEmpty() ? null : joined;
    }

    private static void requireValidDn(String dn, String field) {
        if (dn == null || dn.isBlank()) {
            return;
        }
        try {
            new DN(dn);
        } catch (LDAPException e) {
            throw new IllegalArgumentException("Invalid " + field + " DN: " + e.getMessage());
        }
    }
}
