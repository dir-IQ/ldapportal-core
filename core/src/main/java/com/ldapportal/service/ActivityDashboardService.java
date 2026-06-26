// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.core.alerting.AlertSummary;
import com.ldapportal.dto.audit.AuditQueryCriteria;
import com.ldapportal.core.alerting.AlertSummaryProvider;
import com.ldapportal.core.alerting.AlertingDashboardProvider;
import com.ldapportal.core.dashboard.ReportJobHealth;
import com.ldapportal.core.dashboard.ReportJobHealthProvider;
import com.ldapportal.core.governance.GovernanceDashboardProvider;
import com.ldapportal.core.hr.HrDashboardProvider;
import com.ldapportal.dto.dashboard.ActivityDashboardResponse;
import com.ldapportal.dto.dashboard.ActivityDashboardResponse.*;
import com.ldapportal.entity.*;
import com.ldapportal.entity.enums.*;
import com.ldapportal.entra.repository.EntraSyncStateRepository;
import com.ldapportal.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Builds the activity dashboard (the actions / suggestions / awareness
 * / metrics stream on the home page).
 *
 * <p>Anything governance-, alerting- or HR-related is pulled through
 * the corresponding core SPI ({@link GovernanceDashboardProvider},
 * {@link AlertingDashboardProvider}, {@link HrDashboardProvider},
 * {@link AlertSummaryProvider}, {@link ReportJobHealthProvider}) so
 * this service doesn't import anything from {@code ..ee..}. The
 * {@link com.ldapportal.service.UnifiedDashboardService} already filters
 * actions/suggestions/awareness against entitlement flags before the
 * payload leaves the server, so it's safe for the no-op providers in
 * community to return empty and for the service to build items
 * unconditionally here.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityDashboardService {

    private final DirectoryConnectionRepository dirRepo;
    private final PendingApprovalRepository approvalRepo;
    private final ProvisioningProfileRepository profileRepo;
    private final EntraSyncStateRepository syncStateRepo;
    private final DismissedSuggestionRepository dismissedRepo;
    private final PermissionService permissionService;
    private final AuditQueryService auditQueryService;
    private final ApplicationSettingsService settingsService;
    private final AccountRepository accountRepo;
    private final SyncLinkRepository syncLinkRepo;
    private final MembershipRepository membershipRepo;
    private final SyncSetRepository syncSetRepo;

    private final GovernanceDashboardProvider governance;
    private final AlertSummaryProvider alertSummaryProvider;
    private final AlertingDashboardProvider alertingDashboard;
    private final HrDashboardProvider hrDashboard;
    private final ReportJobHealthProvider reportJobHealth;

    @Transactional(readOnly = true)
    public ActivityDashboardResponse build(AuthPrincipal principal) {
        Set<UUID> dirIds = getDirectoryIds(principal);

        // Directory-sync rollups deep-link into the superadmin-only Directory Sync
        // surface, so only a superadmin gets them — skip the queries for admins.
        // The consumer (UnifiedDashboardService) also gates them on DIRECTORY_SYNC
        // + role before the payload leaves the server.
        Map<MembershipState, Long> syncStates = principal.isSuperadmin()
                ? aggregateMembershipStates() : Map.of();

        List<ActionItem> actions = buildActions(principal, dirIds, syncStates);
        List<SuggestedAction> suggestions = buildSuggestions(principal, dirIds);
        List<AwarenessItem> awareness = buildAwareness(principal, dirIds);
        SummaryMetrics metrics = buildMetrics(dirIds, syncStates);

        return new ActivityDashboardResponse(actions, suggestions, awareness, metrics);
    }

    @Transactional
    public void dismissSuggestion(UUID accountId, String key) {
        if (!dismissedRepo.existsByAccountIdAndSuggestionKey(accountId, key)) {
            DismissedSuggestion d = new DismissedSuggestion();
            d.setAccountId(accountId);
            d.setSuggestionKey(key);
            dismissedRepo.save(d);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Actions
    // ══════════════════════════════════════════════════════════════════════

    private List<ActionItem> buildActions(AuthPrincipal principal, Set<UUID> dirIds,
                                          Map<MembershipState, Long> syncStates) {
        List<ActionItem> items = new ArrayList<>();

        // Pending approvals
        for (UUID dirId : dirIds) {
            long pending = approvalRepo.countByDirectoryIdAndStatus(dirId, ApprovalStatus.PENDING);
            if (pending > 0) {
                String dirName = dirName(dirId);
                items.add(new ActionItem("APPROVAL", "HIGH",
                        pending + " approval request" + (pending > 1 ? "s" : "") + " pending",
                        dirName,
                        "/directories/" + dirId + "/approvals",
                        (int) pending));
            }
        }

        // Access review decisions assigned to this user
        if (!principal.isSuperadmin()) {
            for (GovernanceDashboardProvider.PendingDecisionPrompt p
                    : governance.pendingDecisionPromptsForDirectories(dirIds)) {
                items.add(new ActionItem("REVIEW", "HIGH",
                        p.pendingDecisions() + " review decisions pending in " + p.campaignName(),
                        p.deadlineSummary(),
                        "/directories/" + p.directoryId() + "/access-reviews/" + p.campaignId(),
                        (int) p.pendingDecisions()));
            }
        }

        // Critical/high alerts — uses the AlertSummary (counts are scoped to
        // OPEN by the ee implementation, see AlertService.summary()).
        AlertSummary alertSummary = alertSummaryProvider.summary();
        long criticalAlerts = alertSummary.criticalCount();
        long highAlerts = alertSummary.highCount();
        if (criticalAlerts > 0) {
            items.add(new ActionItem("ALERT", "CRITICAL",
                    criticalAlerts + " critical alert" + (criticalAlerts > 1 ? "s" : ""),
                    "Requires immediate attention",
                    "/superadmin/alerts",
                    (int) criticalAlerts));
        }
        if (highAlerts > 0) {
            items.add(new ActionItem("ALERT", "HIGH",
                    highAlerts + " high-severity alert" + (highAlerts > 1 ? "s" : ""),
                    null,
                    "/superadmin/alerts",
                    (int) highAlerts));
        }

        // Overdue campaigns
        for (GovernanceDashboardProvider.OverdueCampaign o : governance.overdueCampaignsForDirectories(dirIds)) {
            items.add(new ActionItem("CAMPAIGN_OVERDUE", "CRITICAL",
                    o.campaignName() + " is " + o.daysOverdue() + " day(s) overdue",
                    dirName(o.directoryId()),
                    "/directories/" + o.directoryId() + "/access-reviews/" + o.campaignId(),
                    1));
        }

        // Directory-sync apply failures (dead-lettered) + quarantined items awaiting
        // an operator decision. syncStates is empty for non-superadmins, so these
        // never surface for an admin (the consumer gates them again by role).
        long failed = syncStates.getOrDefault(MembershipState.FAILED, 0L);
        if (failed > 0) {
            items.add(new ActionItem("REPLICATION_DEAD_LETTERED", "HIGH",
                    failed + " sync " + (failed == 1 ? "entry" : "entries") + " failed to apply",
                    "Dead-lettered — retried automatically; investigate if persistent",
                    "/superadmin/directory-sync?state=FAILED", clampInt(failed)));
        }
        long review = syncStates.getOrDefault(MembershipState.REVIEW, 0L);
        if (review > 0) {
            items.add(new ActionItem("SYNC_REVIEW_PENDING", "HIGH",
                    review + " sync " + (review == 1 ? "item" : "items") + " awaiting review",
                    "Quarantined changes need an operator decision",
                    "/superadmin/directory-sync?state=REVIEW", clampInt(review)));
        }

        return items;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Suggestions
    // ══════════════════════════════════════════════════════════════════════

    private List<SuggestedAction> buildSuggestions(AuthPrincipal principal, Set<UUID> dirIds) {
        if (!principal.isSuperadmin()) return List.of(); // only superadmins see config suggestions

        Set<String> dismissed = dismissedRepo.findAllByAccountId(principal.id()).stream()
                .map(DismissedSuggestion::getSuggestionKey)
                .collect(java.util.stream.Collectors.toSet());

        List<SuggestedAction> suggestions = new ArrayList<>();
        List<DirectoryConnection> dirs = dirRepo.findAll();

        for (DirectoryConnection dc : dirs) {
            if (!dc.isEnabled()) continue;
            String dirName = dc.getDisplayName();
            UUID dirId = dc.getId();
            boolean isEntra = dc.getDirectoryType() == DirectoryType.ENTRA_ID;

            // Provisioning profiles (LDAP only)
            if (!isEntra) {
                long profileCount = profileRepo.findAllByDirectoryIdOrderByNameAsc(dirId).size();
                if (profileCount == 0) {
                    addIfNotDismissed(suggestions, dismissed, "profiles-" + dirId,
                            "Set up provisioning profiles for " + dirName,
                            "Run the Discovery Wizard to auto-detect OUs and create profiles",
                            "/superadmin/directories/" + dirId + "/discover", "setup");
                }
            }

            // SoD policies — offered unconditionally; filtered at the edition
            // boundary by UnifiedDashboardService when governance isn't
            // entitled.
            addIfNotDismissed(suggestions, dismissed, "sod-" + dirId,
                    "Define SoD policies for " + dirName,
                    "Separation of duties policies detect conflicting group memberships",
                    "/directories/" + dirId + "/sod-policies/new", "policy");

            // Access review campaigns — use the SPI "any history?" check.
            if (!governance.hasAnyCampaignHistory(dirId)) {
                addIfNotDismissed(suggestions, dismissed, "campaign-" + dirId,
                        "Create an access review campaign for " + dirName,
                        "No access reviews have been run for this directory",
                        isEntra ? "/superadmin/access-reviews" : "/directories/" + dirId + "/access-reviews",
                        "review");
            }

            // HR connection
            if (!isEntra && !hrDashboard.hasConnectionForDirectory(dirId)) {
                addIfNotDismissed(suggestions, dismissed, "hr-" + dirId,
                        "Connect an HR system to " + dirName,
                        "Automate lifecycle management with HR integration",
                        "/superadmin/hr", "sync");
            }

            // Alert rules
            if (!alertingDashboard.hasRulesForDirectory(dirId)) {
                addIfNotDismissed(suggestions, dismissed, "alerts-" + dirId,
                        "Initialize alert rules for " + dirName,
                        "Enable continuous access monitoring for this directory",
                        "/superadmin/alert-rules", "alert");
            }

            // Entra initial sync
            if (isEntra && syncStateRepo.findById(dirId).isEmpty()) {
                addIfNotDismissed(suggestions, dismissed, "entra-sync-" + dirId,
                        "Run initial sync for " + dirName,
                        "Pull users and groups from Entra ID into the local cache",
                        "/superadmin/entra/" + dirId, "sync");
            }
        }

        // Global suggestions
        try {
            var settings = settingsService.getEntity();
            if (settings.getSmtpHost() == null || settings.getSmtpHost().isBlank()) {
                addIfNotDismissed(suggestions, dismissed, "smtp",
                        "Configure SMTP for email notifications",
                        "Email delivery is required for approval notifications and alert emails",
                        "/settings", "setup");
            }
        } catch (Exception ignored) {}

        long adminCount = accountRepo.countByRoleAndActiveTrue(AccountRole.ADMIN);
        if (adminCount == 0) {
            addIfNotDismissed(suggestions, dismissed, "admins",
                    "Create admin accounts",
                    "No admin accounts exist — create accounts and assign profile roles",
                    "/superadmin/admins", "setup");
        }

        ReportJobHealth reportJobs = reportJobHealth.health();
        if (reportJobs.enabled() == 0) {
            addIfNotDismissed(suggestions, dismissed, "reports",
                    "Set up scheduled reports",
                    "Automate compliance evidence generation with scheduled report jobs",
                    "/superadmin/reports", "report");
        }

        return suggestions;
    }

    private void addIfNotDismissed(List<SuggestedAction> list, Set<String> dismissed,
                                     String key, String title, String description,
                                     String link, String icon) {
        if (!dismissed.contains(key)) {
            list.add(new SuggestedAction(key, title, description, link, icon));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Awareness
    // ══════════════════════════════════════════════════════════════════════

    private List<AwarenessItem> buildAwareness(AuthPrincipal principal, Set<UUID> dirIds) {
        List<AwarenessItem> items = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();

        // Recent changes (last 24h)
        for (UUID dirId : dirIds) {
            try {
                var events = auditQueryService.query(
                        AuditQueryCriteria.builder().directoryId(dirId).from(now.minusHours(24)).build(),
                        0, 1);
                long count = events.getTotalElements();
                if (count > 0) {
                    items.add(new AwarenessItem("RECENT_CHANGES",
                            count + " change" + (count > 1 ? "s" : "") + " in last 24h",
                            dirName(dirId),
                            "/directories/" + dirId + "/audit"));
                }
            } catch (Exception ignored) {}
        }

        // Upcoming deadlines (campaigns due within 7 days)
        for (GovernanceDashboardProvider.UpcomingDeadline u
                : governance.upcomingDeadlinesForDirectories(dirIds, 7L)) {
            items.add(new AwarenessItem("UPCOMING_DEADLINE",
                    u.campaignName() + " due in " + u.daysUntil() + " day(s)",
                    dirName(u.directoryId()),
                    "/directories/" + u.directoryId() + "/access-reviews/" + u.campaignId()));
        }

        // Entra sync health
        for (UUID dirId : dirIds) {
            syncStateRepo.findById(dirId).ifPresent(state -> {
                if (state.getLastFullSync() != null) {
                    long hoursSince = Duration.between(state.getLastFullSync(), now).toHours();
                    items.add(new AwarenessItem("SYNC_STATUS",
                            "Entra ID synced " + hoursSince + "h ago",
                            dirName(dirId),
                            "/superadmin/entra/" + dirId));
                }
            });
        }

        // Directory-sync changelog health (lag/stall) + reconciliation drift.
        // Superadmin-only surface; the consumer gates again on DIRECTORY_SYNC + role.
        if (principal.isSuperadmin()) {
            addSyncHealthAwareness(items);
        }

        return items;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Metrics
    // ══════════════════════════════════════════════════════════════════════

    private SummaryMetrics buildMetrics(Set<UUID> dirIds, Map<MembershipState, Long> syncStates) {
        // Scope the SoD count to the caller's directories. The governance SPI
        // gives us per-directory counts; sum them to produce the metric.
        long openSod = 0;
        for (UUID dirId : dirIds) {
            openSod += governance.directoryCounts(dirId).openSodViolations();
        }
        long openAlerts = alertSummaryProvider.summary().openCount();
        long activeCampaigns = governance.activeCampaignProgress().size();

        // System-wide dead-letter count = membership rows stuck in FAILED. Empty
        // (zero) for non-superadmins and editions without the DIRECTORY_SYNC feature.
        long replicationDeadLettered = syncStates.getOrDefault(MembershipState.FAILED, 0L);

        // User/group counts would require LDAP queries — use 0 for now
        // (the current dashboard already provides these via the compliance dashboard)
        return new SummaryMetrics(0, 0, openSod, openAlerts, activeCampaigns, replicationDeadLettered);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private Set<UUID> getDirectoryIds(AuthPrincipal principal) {
        if (principal.isSuperadmin()) {
            Set<UUID> ids = new HashSet<>();
            dirRepo.findAll().forEach(dc -> ids.add(dc.getId()));
            return ids;
        }
        return permissionService.getAuthorizedDirectoryIds(principal);
    }

    private String dirName(UUID dirId) {
        return dirRepo.findById(dirId).map(DirectoryConnection::getDisplayName).orElse("");
    }

    // ── Directory-sync rollups ──────────────────────────────────────────────────

    /** System-wide membership counts by state (failed / review / …) in one query. */
    private Map<MembershipState, Long> aggregateMembershipStates() {
        Map<MembershipState, Long> byState = new EnumMap<>(MembershipState.class);
        for (MembershipStateCount c : membershipRepo.countGroupedByState()) {
            byState.merge(c.getState(), c.getCnt(), Long::sum);
        }
        return byState;
    }

    /**
     * Two directory-sync awareness items, each an aggregate that deep-links to the
     * Directory Sync surface for the detail: changelog capture that is lagging or
     * stalled ({@code REPLICATION_LAG_HIGH}), and reconciliation drift from the last
     * cached content verify ({@code RECONCILIATION_DRIFT_OPEN}).
     */
    private void addSyncHealthAwareness(List<AwarenessItem> items) {
        long degraded = 0;
        for (Object[] row : syncLinkRepo.countChangelogLinksByHealth()) {
            SyncChangelogHealth health = (SyncChangelogHealth) row[0];
            if (health != SyncChangelogHealth.HEALTHY) {
                degraded += ((Number) row[1]).longValue();
            }
        }
        if (degraded > 0) {
            Long maxLag = syncLinkRepo.maxChangelogLag();
            String detail = (maxLag != null && maxLag > 0)
                    ? "Up to " + maxLag + " changes behind — capture is lagging or stalled"
                    : "Changelog capture is lagging or stalled";
            items.add(new AwarenessItem("REPLICATION_LAG_HIGH",
                    degraded + " sync link" + (degraded == 1 ? "" : "s") + " behind or stalled",
                    detail, "/superadmin/directory-sync"));
        }

        long driftSets = 0;
        long driftEntries = 0;
        for (SyncSet set : syncSetRepo.findAllByEnabledTrue()) {
            if (set.getLastVerifiedAt() == null) {
                continue; // never verified — claim no drift
            }
            long drift = nz(set.getVerifyMissingCount())
                    + nz(set.getVerifyOrphanCount())
                    + nz(set.getVerifyMismatchCount());
            if (drift > 0) {
                driftSets++;
                driftEntries += drift;
            }
        }
        if (driftEntries > 0) {
            items.add(new AwarenessItem("RECONCILIATION_DRIFT_OPEN",
                    driftEntries + (driftEntries == 1 ? " entry" : " entries") + " differ from source",
                    "Across " + driftSets + " sync set" + (driftSets == 1 ? "" : "s")
                            + " — last content verify found drift",
                    "/superadmin/directory-sync"));
        }
    }

    private static long nz(Integer v) {
        return v == null ? 0L : v;
    }

    private static int clampInt(long v) {
        return (int) Math.min(v, Integer.MAX_VALUE);
    }
}
