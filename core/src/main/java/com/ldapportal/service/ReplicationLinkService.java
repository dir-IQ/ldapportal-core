// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.replication.ReplicationLinkRequest;
import com.ldapportal.dto.replication.ReplicationLinkResponse;
import com.ldapportal.dto.replication.ReplicationLinkResponse.LinkHealth;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.ReplicationLinkAttrMapping;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.ChangelogHealth;
import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReconciliationFindingStatus;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;
import com.ldapportal.entity.enums.ReplicationCaptureMode;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.replication.reconcile.ReconciliationService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ReconciliationFindingRepository;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.ldapportal.entity.enums.AuditAction.RECONCILIATION_CONFIG_UPDATED;

/**
 * CRUD for replication links. Exposed via
 * {@code /api/v1/superadmin/replication-links/*}; SUPERADMIN only.
 *
 * <p>Health counts (pending / failed / dead-lettered / last-delivered)
 * are computed in a single batched query against
 * {@link ReplicationEventRepository#findHealthRollup} rather than
 * triggering one query per link.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplicationLinkService {

    private final ReplicationLinkRepository  linkRepo;
    private final ReplicationEventRepository eventRepo;
    private final ReconciliationFindingRepository findingRepo;
    private final DirectoryConnectionRepository dirRepo;
    private final AuditService               auditService;
    private final ReconciliationService      reconciliationService;

    /** Floor on the reconciliation repeat interval — 1 hour. Mirrors the DB CHECK. */
    private static final int RECONCILE_MIN_INTERVAL_SECS = 3600;

    /** Default changelog base DN when CHANGELOG capture is enabled without one. */
    private static final String DEFAULT_CHANGELOG_BASE_DN = "cn=changelog";

    @Transactional(readOnly = true)
    public List<ReplicationLinkResponse> listLinks() {
        List<ReplicationLink> links = linkRepo.findAll();
        if (links.isEmpty()) return List.of();
        Map<UUID, LinkHealth> health = healthByLinkId(links);
        return links.stream()
                .map(l -> ReplicationLinkResponse.from(l, health.getOrDefault(l.getId(), LinkHealth.empty())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReplicationLinkResponse getLink(UUID id) {
        ReplicationLink link = require(id);
        LinkHealth health = healthByLinkId(List.of(link)).getOrDefault(id, LinkHealth.empty());
        return ReplicationLinkResponse.from(link, health);
    }

    @Transactional
    public ReplicationLinkResponse createLink(AuthPrincipal principal, ReplicationLinkRequest req) {
        validateRequest(req, null);
        ReplicationLink link = new ReplicationLink();
        ReplicationCaptureMode captureBefore = link.getCaptureMode();
        applyRequest(link, req);
        link = linkRepo.save(link);
        log.info("Replication link created: {} ({} → {})",
                link.getId(), link.getSourceDirectory().getDisplayName(),
                link.getTargetDirectory().getDisplayName());
        auditService.recordSystemEvent(principal, AuditAction.REPLICATION_LINK_CREATED, auditDetail(link));
        if (link.isReconcileEnabled()) {
            auditService.recordSystemEvent(principal, RECONCILIATION_CONFIG_UPDATED, reconcileAuditDetail(link));
        }
        afterCaptureModeSwitch(principal, link, captureBefore);
        return ReplicationLinkResponse.from(link, LinkHealth.empty());
    }

    @Transactional
    public ReplicationLinkResponse updateLink(AuthPrincipal principal, UUID id, ReplicationLinkRequest req) {
        ReplicationLink link = require(id);
        boolean wasEnabled = link.isEnabled();
        ReplicationCaptureMode captureBefore = link.getCaptureMode();
        String excludeFilterBefore = link.getExcludeFilter();
        String reconcileBefore = reconcileSignature(link);
        validateRequest(req, id);
        // Drop the existing mapping rows BEFORE staging the new ones,
        // not in the same flush. The mapping table has a composite PK
        // (link_id, source_attr); when an update preserves any
        // source_attr value, Hibernate's default action ordering
        // (orphan-remove → insert → delete) fires the INSERT for the
        // new row before the DELETE of the old one with the same PK,
        // tripping a unique-constraint violation. Two-step replace
        // (clear+flush, then add) bypasses the ordering issue.
        if (!link.getAttributeMappings().isEmpty()) {
            link.getAttributeMappings().clear();
            linkRepo.saveAndFlush(link);
        }
        applyRequest(link, req);
        link = linkRepo.save(link);
        Map<UUID, LinkHealth> health = healthByLinkId(List.of(link));

        // Always record the general update first; if the enabled flag
        // also flipped, follow with the specific ENABLED / DISABLED
        // action so the audit log carries both signals. Operators
        // reviewing 'who turned this off' shouldn't have to read the
        // generic UPDATE detail map to find the answer.
        auditService.recordSystemEvent(principal, AuditAction.REPLICATION_LINK_UPDATED, auditDetail(link));
        if (wasEnabled != link.isEnabled()) {
            AuditAction toggle = link.isEnabled()
                    ? AuditAction.REPLICATION_LINK_ENABLED
                    : AuditAction.REPLICATION_LINK_DISABLED;
            auditService.recordSystemEvent(principal, toggle, auditDetail(link));
        }
        // Record reconciliation-config changes distinctly so an operator
        // reviewing "who turned reconciliation on / changed the schedule"
        // doesn't have to diff the generic UPDATE detail map.
        if (!reconcileSignature(link).equals(reconcileBefore)) {
            auditService.recordSystemEvent(principal, RECONCILIATION_CONFIG_UPDATED, reconcileAuditDetail(link));
        }
        afterCaptureModeSwitch(principal, link, captureBefore);
        // Converge the target via a one-off reconcile (after commit) when the
        // replicated set changes without a capture-mode switch (which already
        // schedules one): the link was just ENABLED (§7A.8 — "enabling a link in
        // either mode auto-triggers") or its exclude filter changed (§7B.5).
        // Single-flight collapses any overlap; only for an enabled link.
        boolean captureChanged = link.getCaptureMode() != captureBefore;
        boolean justEnabled    = !wasEnabled && link.isEnabled();
        boolean filterChanged  = !Objects.equals(link.getExcludeFilter(), excludeFilterBefore);
        if (!captureChanged && link.isEnabled() && (justEnabled || filterChanged)) {
            UUID linkId = link.getId();
            afterCommit(() -> triggerSeamReconcile(linkId, principal));
        }

        return ReplicationLinkResponse.from(link, health.getOrDefault(id, LinkHealth.empty()));
    }

    // ── Changelog operator remediation (§7A.12) ───────────────────────────────

    /**
     * Reseed: drop the cursor so the next poll re-seeds from the current source
     * head (no history replay). Recovers a CURSOR_RESET / wedged link. A
     * follow-up reconciliation is fired (after commit) to bring pre-existing
     * entries into parity, mirroring the mode-switch seam (§2.1 / §7A.8).
     */
    @Transactional
    public ReplicationLinkResponse reseedChangelogCursor(AuthPrincipal principal, UUID id) {
        ReplicationLink link = requireChangelogLink(id);
        // Materialise the audit detail BEFORE the clearAutomatically @Modifying
        // query below: that query calls EntityManager.clear(), detaching `link`
        // and its LAZY sourceDirectory/targetDirectory proxies — reading them
        // afterwards would throw LazyInitializationException.
        Map<String, Object> detail = remediationDetail(link, "reseed", null);
        boolean enabled = link.isEnabled();
        linkRepo.reseedChangelogCursor(id);
        auditService.recordSystemEvent(principal, AuditAction.REPLICATION_CHANGELOG_REMEDIATED, detail);
        if (enabled) {
            afterCommit(() -> triggerSeamReconcile(id, principal));
        }
        return getLink(id);
    }

    /** Rewind: set the cursor to an operator-supplied changeNumber (e.g. to re-process a span). */
    @Transactional
    public ReplicationLinkResponse rewindChangelogCursor(AuthPrincipal principal, UUID id, long target) {
        if (target < 0) {
            throw new IllegalArgumentException("changeNumber must be >= 0");
        }
        ReplicationLink link = requireChangelogLink(id);
        Map<String, Object> detail = remediationDetail(link, "rewind", target);   // before clear (see reseed)
        linkRepo.rewindChangelogCursor(id, target);
        auditService.recordSystemEvent(principal, AuditAction.REPLICATION_CHANGELOG_REMEDIATED, detail);
        return getLink(id);
    }

    /** Re-enable: clear a degraded health/error and retry from the current cursor. */
    @Transactional
    public ReplicationLinkResponse reEnableChangelogPoll(AuthPrincipal principal, UUID id) {
        ReplicationLink link = requireChangelogLink(id);
        Map<String, Object> detail = remediationDetail(link, "re-enable", null);  // before clear (see reseed)
        linkRepo.clearChangelogHealthError(id);
        auditService.recordSystemEvent(principal, AuditAction.REPLICATION_CHANGELOG_REMEDIATED, detail);
        return getLink(id);
    }

    private ReplicationLink requireChangelogLink(UUID id) {
        ReplicationLink link = require(id);
        if (link.getCaptureMode() != ReplicationCaptureMode.CHANGELOG) {
            throw new IllegalArgumentException(
                    "Link " + id + " is not a changelog-capture link; remediation does not apply");
        }
        return link;
    }

    private static Map<String, Object> remediationDetail(ReplicationLink link, String operation, Long target) {
        Map<String, Object> detail = auditDetail(link);
        detail.put("remediation", operation);
        if (target != null) detail.put("targetChangeNumber", target);
        return detail;
    }

    /** Run a callback after the current tx commits (so a reconcile sees the persisted state). */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    @Transactional
    public void deleteLink(AuthPrincipal principal, UUID id) {
        ReplicationLink link = require(id);
        Map<String, Object> detail = auditDetail(link);
        // FK ON DELETE CASCADE (V7 migration) wipes every queued event
        // for this link with no per-event audit. Capture the counts
        // before the cascade fires so the compliance trail at least
        // records how many unresolved events were destroyed — without
        // this an operator nuking a wedged link erases all forensic
        // evidence of the failures.
        LinkHealth health = healthByLinkId(List.of(link))
                .getOrDefault(id, LinkHealth.empty());
        detail.put("eventsPending",       health.pendingCount());
        detail.put("eventsFailed",        health.failedCount());
        detail.put("eventsDeadLettered",  health.deadLetteredCount());

        linkRepo.delete(link);
        auditService.recordSystemEvent(principal, AuditAction.REPLICATION_LINK_DELETED, detail);
        log.info("Replication link deleted: {} (cascaded pending={} failed={} dead-lettered={})",
                id, health.pendingCount(), health.failedCount(), health.deadLetteredCount());
    }

    private static Map<String, Object> auditDetail(ReplicationLink link) {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("linkId",              link.getId().toString());
        detail.put("displayName",         link.getDisplayName());
        detail.put("sourceDirectoryId",   link.getSourceDirectory().getId().toString());
        detail.put("sourceDirectoryName", link.getSourceDirectory().getDisplayName());
        detail.put("targetDirectoryId",   link.getTargetDirectory().getId().toString());
        detail.put("targetDirectoryName", link.getTargetDirectory().getDisplayName());
        detail.put("enabled",             link.isEnabled());
        detail.put("captureMode",         link.getCaptureMode().name());
        return detail;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ReplicationLink require(UUID id) {
        return linkRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ReplicationLink", id.toString()));
    }

    private void validateRequest(ReplicationLinkRequest req, UUID updatingId) {
        if (req.sourceDirectoryId().equals(req.targetDirectoryId())) {
            throw new IllegalArgumentException(
                    "sourceDirectoryId and targetDirectoryId must differ");
        }
        // Both base DNs set OR both unset — mirrors the DB CHECK
        // constraint. Catching it here gives a 400 with a useful
        // message instead of a 500 with a constraint-violation
        // stack trace.
        boolean sourceSet = req.sourceBaseDn() != null && !req.sourceBaseDn().isBlank();
        boolean targetSet = req.targetBaseDn() != null && !req.targetBaseDn().isBlank();
        if (sourceSet != targetSet) {
            throw new IllegalArgumentException(
                    "sourceBaseDn and targetBaseDn must both be set or both null");
        }
        // Bidirectional-rejection guard (create only). Reject A→B when a
        // B→A link already exists, regardless of either link's enabled
        // state — rejecting only enabled links would let an operator
        // pause B→A, create A→B, then re-enable B→A and re-arm a hidden
        // loop. v1 replication has no origin stamping to break such loops.
        if (updatingId == null) {
            linkRepo.findFirstBySourceDirectoryIdAndTargetDirectoryId(
                            req.targetDirectoryId(), req.sourceDirectoryId())
                    .ifPresent(existing -> {
                        throw new IllegalArgumentException(
                                "Would create reverse of existing link " + existing.getId()
                              + " — bidirectional configurations require origin-stamped "
                              + "events, not in v1. Disable or delete the reverse link first.");
                    });
        }
        // Reconciliation schedule must be complete and sane when enabled —
        // mirrors the DB CHECK so the operator gets a 400 instead of a 500.
        if (req.reconcileEnabled()) {
            if (req.reconcileFirstRunAt() == null) {
                throw new IllegalArgumentException(
                        "reconcileFirstRunAt is required when reconciliation is enabled");
            }
            if (req.reconcileIntervalSecs() == null) {
                throw new IllegalArgumentException(
                        "reconcileIntervalSecs is required when reconciliation is enabled");
            }
            if (req.reconcileIntervalSecs() < RECONCILE_MIN_INTERVAL_SECS) {
                throw new IllegalArgumentException(
                        "reconcileIntervalSecs must be at least " + RECONCILE_MIN_INTERVAL_SECS
                      + " seconds (1 hour)");
            }
        }
        validateChangelogCapture(req);
    }

    /**
     * Changelog-capture + exclude-filter validation (§9). Mirrors the DB
     * CHECK constraints so the operator gets a 400 instead of a 500:
     * CHANGELOG mode requires a {@code changelogFormat}, and v1 supports only
     * {@code DSEE_CHANGELOG} (OUD / cn=changelog). The exclude filter, if
     * present, must parse as an RFC 4515 filter — it applies to either mode.
     */
    private void validateChangelogCapture(ReplicationLinkRequest req) {
        ReplicationCaptureMode mode = req.captureMode() != null
                ? req.captureMode() : ReplicationCaptureMode.APP_INTERCEPT;
        if (mode == ReplicationCaptureMode.CHANGELOG) {
            if (req.changelogFormat() == null) {
                throw new IllegalArgumentException(
                        "changelogFormat is required when captureMode is CHANGELOG");
            }
            if (req.changelogFormat() != ChangelogFormat.DSEE_CHANGELOG) {
                throw new IllegalArgumentException(
                        "changelog capture supports OUD / cn=changelog only in this version "
                      + "(changelogFormat must be DSEE_CHANGELOG)");
            }
        }
        if (req.excludeFilter() != null && !req.excludeFilter().isBlank()) {
            try {
                Filter.create(req.excludeFilter());
            } catch (LDAPException e) {
                throw new IllegalArgumentException(
                        "excludeFilter is not a valid RFC 4515 filter: " + e.getMessage());
            }
        }
    }

    private void applyRequest(ReplicationLink link, ReplicationLinkRequest req) {
        link.setDisplayName(req.displayName());
        link.setSourceDirectory(requireDirectory(req.sourceDirectoryId()));
        link.setTargetDirectory(requireDirectory(req.targetDirectoryId()));
        link.setSourceBaseDn(blankToNull(req.sourceBaseDn()));
        link.setTargetBaseDn(blankToNull(req.targetBaseDn()));
        link.setEnabled(req.enabled());
        link.setAutoCreateOnMissing(req.autoCreateOnMissing());
        applyReconcileConfig(link, req);
        applyChangelogConfig(link, req);

        // Attribute mappings: replace the whole list. orphanRemoval=true
        // on the @OneToMany makes JPA clean up removed rows.
        link.getAttributeMappings().clear();
        if (req.attributeMappings() != null) {
            for (var rule : req.attributeMappings()) {
                ReplicationLinkAttrMapping m = new ReplicationLinkAttrMapping();
                m.setLink(link);
                m.setSourceAttr(rule.sourceAttr());
                m.setTargetAttr(rule.targetAttr());
                m.setValueTemplate(blankToNull(rule.valueTemplate()));
                link.getAttributeMappings().add(m);
            }
        }
    }

    /**
     * Apply the reconciliation config and (re)compute the next-run pointer.
     * Recomputes {@code reconcileNextRunAt} when first enabling or when the
     * operator changed the start time or cadence; otherwise the running
     * schedule is preserved across unrelated edits. Disabling clears the
     * pointer so the scheduler ignores the link (history is kept in
     * {@code reconcileLastRunAt}).
     */
    private void applyReconcileConfig(ReplicationLink link, ReplicationLinkRequest req) {
        boolean wasEnabled = link.isReconcileEnabled();
        OffsetDateTime oldFirstRun = link.getReconcileFirstRunAt();
        Integer oldInterval = link.getReconcileIntervalSecs();

        link.setReconcileEnabled(req.reconcileEnabled());
        link.setReconcileMode(req.reconcileMode() != null ? req.reconcileMode() : ReconcileMode.REVIEW);
        link.setReconcileDeleteAction(
                req.reconcileDeleteAction() != null ? req.reconcileDeleteAction() : ReconcileDeleteAction.REVIEW);
        link.setReconcileFirstRunAt(req.reconcileFirstRunAt());
        link.setReconcileIntervalSecs(req.reconcileIntervalSecs());

        if (!req.reconcileEnabled()) {
            link.setReconcileNextRunAt(null);
            return;
        }
        boolean scheduleChanged = !wasEnabled
                || !Objects.equals(oldFirstRun, req.reconcileFirstRunAt())
                || !Objects.equals(oldInterval, req.reconcileIntervalSecs());
        if (scheduleChanged || link.getReconcileNextRunAt() == null) {
            link.setReconcileNextRunAt(req.reconcileFirstRunAt());
        }
    }

    /**
     * Apply the changelog-capture config. CHANGELOG mode stores the format and
     * a base DN (defaulted to {@code cn=changelog}); APP_INTERCEPT nulls the
     * changelog config out. The {@code excludeFilter} applies to either mode.
     *
     * <p>Switching capture modes <b>resets the cursor and health</b> so a
     * newly-enabled CHANGELOG link re-seeds from the current source head
     * rather than replaying history (§2.1); the seam between the old and new
     * mode is closed by a reconciliation run (§7A.8, wired in a later phase).
     */
    private void applyChangelogConfig(ReplicationLink link, ReplicationLinkRequest req) {
        ReplicationCaptureMode previous = link.getCaptureMode();
        ReplicationCaptureMode mode = req.captureMode() != null
                ? req.captureMode() : ReplicationCaptureMode.APP_INTERCEPT;

        link.setCaptureMode(mode);
        link.setExcludeFilter(blankToNull(req.excludeFilter()));

        if (mode == ReplicationCaptureMode.CHANGELOG) {
            link.setChangelogFormat(req.changelogFormat());
            String baseDn = blankToNull(req.changelogBaseDn());
            link.setChangelogBaseDn(baseDn != null ? baseDn : DEFAULT_CHANGELOG_BASE_DN);
        } else {
            link.setChangelogFormat(null);
            link.setChangelogBaseDn(null);
        }

        if (previous != mode) {
            // Re-seed cleanly under the new mode: drop the cursor + observed
            // head, clear any stale health/error from the prior mode, and
            // release any poll lease so the re-enabled link isn't blocked by a
            // stale claim until the poller's stale-claim sweep runs.
            link.setChangelogLastChangeNumber(null);
            link.setChangelogSourceLastChangeNumber(null);
            link.setChangelogLastPolledAt(null);
            link.setChangelogLastError(null);
            link.setChangelogLastErrorAt(null);
            link.setChangelogPollClaimedAt(null);
            link.setChangelogHealth(ChangelogHealth.HEALTHY);
        }
    }

    /**
     * Close the capture-mode seam (§7A.8). Flipping {@code APP_INTERCEPT} ↔
     * {@code CHANGELOG} (or creating a link directly in CHANGELOG) leaves a
     * window where a write is caught by neither path. On the transition: audit
     * the capture enable/disable and fire a one-off {@code MANUAL} reconciliation
     * to converge the target. The reconcile is registered to run <b>after this
     * transaction commits</b> — otherwise the run, in its own {@code REQUIRES_NEW}
     * tx, couldn't see the not-yet-committed link.
     */
    private void afterCaptureModeSwitch(AuthPrincipal principal, ReplicationLink link,
                                        ReplicationCaptureMode before) {
        if (link.getCaptureMode() == before) return;

        boolean nowChangelog = link.getCaptureMode() == ReplicationCaptureMode.CHANGELOG;
        auditService.recordSystemEvent(principal,
                nowChangelog ? AuditAction.REPLICATION_CHANGELOG_CAPTURE_ENABLED
                             : AuditAction.REPLICATION_CHANGELOG_CAPTURE_DISABLED,
                auditDetail(link));

        // Don't reconcile a disabled link: its corrective events wouldn't be
        // delivered now and would pile up to flood the target when it's later
        // enabled. The seam only exists while replication is actually active.
        if (!link.isEnabled()) return;

        UUID linkId = link.getId();
        afterCommit(() -> triggerSeamReconcile(linkId, principal));
    }

    private void triggerSeamReconcile(UUID linkId, AuthPrincipal principal) {
        try {
            reconciliationService.trigger(linkId, ReconciliationRunTrigger.MANUAL, principal);
        } catch (RuntimeException ex) {
            log.error("Capture-switch reconciliation trigger failed for link {}: {}", linkId, ex.toString());
        }
    }

    /**
     * Operator-facing reconciliation config as a comparable signature.
     * Excludes the derived next/last-run pointers so only intentional
     * config edits trigger a {@code RECONCILIATION_CONFIG_UPDATED} audit.
     */
    private static String reconcileSignature(ReplicationLink l) {
        return l.isReconcileEnabled()
                + "|" + l.getReconcileMode()
                + "|" + l.getReconcileFirstRunAt()
                + "|" + l.getReconcileIntervalSecs()
                + "|" + l.getReconcileDeleteAction();
    }

    private static Map<String, Object> reconcileAuditDetail(ReplicationLink link) {
        Map<String, Object> detail = auditDetail(link);
        detail.put("reconcileEnabled",      link.isReconcileEnabled());
        detail.put("reconcileMode",         link.getReconcileMode().name());
        detail.put("reconcileDeleteAction", link.getReconcileDeleteAction().name());
        if (link.getReconcileIntervalSecs() != null) {
            detail.put("reconcileIntervalSecs", link.getReconcileIntervalSecs());
        }
        if (link.getReconcileFirstRunAt() != null) {
            detail.put("reconcileFirstRunAt", link.getReconcileFirstRunAt().toString());
        }
        return detail;
    }

    private DirectoryConnection requireDirectory(UUID id) {
        return dirRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", id.toString()));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Map link ids → {@link LinkHealth}. Returns empty for any link
     * without events; the caller defaults to {@code LinkHealth.empty()}.
     */
    private Map<UUID, LinkHealth> healthByLinkId(List<ReplicationLink> links) {
        if (links.isEmpty()) return Map.of();
        List<UUID> ids = links.stream().map(ReplicationLink::getId).toList();

        // Open (PROPOSED) reconciliation findings per link — one batched
        // aggregate, same shape as the event health rollup.
        Map<UUID, Long> openFindings = new HashMap<>();
        for (Object[] row : findingRepo.countByLinkIdsAndStatus(ids, ReconciliationFindingStatus.PROPOSED)) {
            openFindings.put((UUID) row[0], ((Number) row[1]).longValue());
        }

        List<Object[]> rows = eventRepo.findHealthRollup(ids);
        Map<UUID, LinkHealth> result = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            UUID linkId = (UUID) row[0];
            long pending      = ((Number) row[1]).longValue();
            long failed       = ((Number) row[2]).longValue();
            long deadLettered = ((Number) row[3]).longValue();
            OffsetDateTime lastDelivered = (OffsetDateTime) row[4];
            result.put(linkId, new LinkHealth(pending, failed, deadLettered, lastDelivered,
                    openFindings.getOrDefault(linkId, 0L)));
        }
        // A link with open findings but no events won't appear in the health
        // rollup; fold those in so the badge still surfaces.
        for (Map.Entry<UUID, Long> e : openFindings.entrySet()) {
            result.computeIfAbsent(e.getKey(),
                    id -> new LinkHealth(0L, 0L, 0L, null, e.getValue()));
        }
        return result;
    }
}
