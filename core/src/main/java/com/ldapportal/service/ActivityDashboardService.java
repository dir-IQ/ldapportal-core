// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.core.alerting.AlertSummary;
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

    private final GovernanceDashboardProvider governance;
    private final AlertSummaryProvider alertSummaryProvider;
    private final AlertingDashboardProvider alertingDashboard;
    private final HrDashboardProvider hrDashboard;
    private final ReportJobHealthProvider reportJobHealth;
    private final com.ldapportal.repository.ReplicationEventRepository replicationEventRepo;

    @Transactional(readOnly = true)
    public ActivityDashboardResponse build(AuthPrincipal principal) {
        Set<UUID> dirIds = getDirectoryIds(principal);

        List<ActionItem> actions = buildActions(principal, dirIds);
        List<SuggestedAction> suggestions = buildSuggestions(principal, dirIds);
        List<AwarenessItem> awareness = buildAwareness(dirIds);
        SummaryMetrics metrics = buildMetrics(dirIds);

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

    private List<ActionItem> buildActions(AuthPrincipal principal, Set<UUID> dirIds) {
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

        // Dead-lettered replication events — operator action required:
        // review the event, then retry / skip / acknowledge.
        // UnifiedDashboardService filters this out when DIRECTORY_SYNC
        // is withheld.
        long replicationDeadLettered = replicationEventRepo
                .countByStatus(com.ldapportal.entity.enums.ReplicationEventStatus.DEAD_LETTERED);
        if (replicationDeadLettered > 0) {
            String title = replicationDeadLettered == 1
                    ? "1 replication event failed delivery"
                    : replicationDeadLettered + " replication events failed delivery";
            items.add(new ActionItem("REPLICATION_DEAD_LETTERED", "HIGH",
                    title,
                    "Review or acknowledge to clear",
                    "/superadmin/directory-sync?status=DEAD_LETTERED",
                    Math.toIntExact(Math.min(replicationDeadLettered, Integer.MAX_VALUE))));
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

    private List<AwarenessItem> buildAwareness(Set<UUID> dirIds) {
        List<AwarenessItem> items = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();

        // Recent changes (last 24h)
        for (UUID dirId : dirIds) {
            try {
                var events = auditQueryService.query(dirId, null, null, null, null,
                        now.minusHours(24), null, 0, 1);
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

        // Replication lag — links with unresolved events older than 5
        // minutes. Informational only (not a call to action); the
        // operator may already know the target is down. Filtered out
        // when DIRECTORY_SYNC entitlement is absent.
        long laggingLinks = replicationEventRepo.countLinksLaggingSince(
                java.time.OffsetDateTime.now().minus(java.time.Duration.ofMinutes(5)));
        if (laggingLinks > 0) {
            String title = laggingLinks == 1
                    ? "Replication lag exceeds 5 minutes on 1 link"
                    : "Replication lag exceeds 5 minutes on " + laggingLinks + " links";
            items.add(new AwarenessItem("REPLICATION_LAG_HIGH",
                    title,
                    "Target directory may be unreachable or write-throttled",
                    "/superadmin/directory-sync"));
        }

        return items;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Metrics
    // ══════════════════════════════════════════════════════════════════════

    private SummaryMetrics buildMetrics(Set<UUID> dirIds) {
        // Scope the SoD count to the caller's directories. The governance SPI
        // gives us per-directory counts; sum them to produce the metric.
        long openSod = 0;
        for (UUID dirId : dirIds) {
            openSod += governance.directoryCounts(dirId).openSodViolations();
        }
        long openAlerts = alertSummaryProvider.summary().openCount();
        long activeCampaigns = governance.activeCampaignProgress().size();

        // Directory-sync metric: count dead-lettered replication
        // events. Zero when the feature isn't in use, regardless of
        // entitlement — the UnifiedDashboardService zeroes the field
        // again when DIRECTORY_SYNC is withheld, but a zero here keeps
        // the card hidden by default rather than relying on the filter.
        long replicationDeadLettered = replicationEventRepo
                .countByStatus(com.ldapportal.entity.enums.ReplicationEventStatus.DEAD_LETTERED);

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
}
