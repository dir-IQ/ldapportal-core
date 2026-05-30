// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.alerting.AlertSummary;
import com.ldapportal.core.alerting.AlertSummaryProvider;
import com.ldapportal.dto.audit.AuditEventResponse;
import com.ldapportal.dto.dashboard.ActivityDashboardResponse;
import com.ldapportal.dto.dashboard.AdminDashboardDto;
import com.ldapportal.dto.dashboard.ComplianceDashboardDto;
import com.ldapportal.dto.dashboard.UnifiedDashboardDto;
import com.ldapportal.dto.dashboard.UnifiedDashboardDto.*;
import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.core.dashboard.ReportJobHealth;
import com.ldapportal.core.dashboard.ReportJobHealthProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * Composes the dashboard payload for both admin and superadmin principals and
 * applies feature-flag filtering (compliance, HR integration) server-side.
 */
@Service
@RequiredArgsConstructor
public class UnifiedDashboardService {

    private final DashboardService dashboardService;
    private final AdminDashboardService adminDashboardService;
    private final ActivityDashboardService activityDashboardService;
    private final AlertSummaryProvider alertSummaryProvider;
    private final ApplicationSettingsService settingsService;
    private final ReportJobHealthProvider reportJobHealthProvider;
    private final EntitlementService entitlementService;

    private static final Set<String> COMPLIANCE_ACTION_TYPES = Set.of("REVIEW", "CAMPAIGN_OVERDUE");
    private static final Set<String> COMPLIANCE_AWARENESS_TYPES = Set.of("UPCOMING_DEADLINE");

    @Transactional(readOnly = true)
    public UnifiedDashboardDto getDashboard(AuthPrincipal principal) {
        // Sourced from the entitlement layer so this call site doesn't care
        // whether the backing store is ApplicationSettings (Phase 1) or a
        // signed license (Phase 6).
        boolean complianceEnabled = entitlementService.has(Entitlement.GOVERNANCE);
        boolean hrEnabled = entitlementService.has(Entitlement.HR_SYNC);
        boolean alertingEnabled = entitlementService.has(Entitlement.ALERTING);
        boolean directorySyncEnabled = entitlementService.has(Entitlement.DIRECTORY_SYNC);

        AlertSummary alertSummary = alertSummaryProvider.summary();
        ActivityDashboardResponse activity = activityDashboardService.build(principal);

        ReportJobHealth reportJobs = reportJobHealthProvider.health();
        long enabledReportJobs = reportJobs.enabled();
        long failedReportJobs = reportJobs.failed();

        MetricsDto metrics;
        ApprovalAgingDto aging;
        boolean approvalsConfigured;
        List<CampaignProgressDto> campaignProgress;
        List<DirectoryStatDto> directories;
        List<ProfileStatDto> profiles;
        List<AuditEventResponse> recentActivity;
        String firstDirectoryId;

        if (principal.isSuperadmin()) {
            ComplianceDashboardDto c = dashboardService.getDashboard();
            long active = c.campaignProgress() == null ? 0L : c.campaignProgress().size();
            metrics = new MetricsDto(
                    c.totalUsers(), c.totalGroups(), c.totalPendingApprovals(),
                    c.openSodViolations(), active, c.campaignCompletionPercent(), c.overdueCampaigns());
            aging = mapAging(c.approvalAging());
            approvalsConfigured = c.approvalsConfigured();
            campaignProgress = mapCampaigns(c.campaignProgress());
            directories = mapDirs(c.directories());
            profiles = List.of();  // superadmin dashboard shows Directories, not Profiles
            recentActivity = c.recentAudit();
            firstDirectoryId = null;
        } else {
            AdminDashboardDto a = adminDashboardService.getDashboard(principal);
            metrics = new MetricsDto(
                    a.totalUsers(), a.totalGroups(), a.totalPendingApprovals(),
                    a.openSodViolations(), a.activeAccessReviewCampaigns(),
                    a.campaignCompletionPercent(), a.overdueCampaigns());
            aging = mapAgingAdmin(a.approvalAging());
            approvalsConfigured = a.approvalsConfigured();
            campaignProgress = mapCampaignsAdmin(a.campaignProgress());
            directories = mapDirsAdmin(a.directories());
            profiles = mapProfilesAdmin(a.profiles());
            recentActivity = a.recentActivity();
            firstDirectoryId = a.firstDirectoryId();
        }

        if (!complianceEnabled) {
            metrics = new MetricsDto(
                    metrics.totalUsers(), metrics.totalGroups(), metrics.pendingApprovals(),
                    0L, 0L, null, 0L);
            campaignProgress = List.of();
        }

        List<ActionItem> actions = filterActions(activity.actions(), complianceEnabled, directorySyncEnabled);
        List<SuggestedAction> suggestions = filterSuggestions(activity.suggestions(), complianceEnabled, hrEnabled, alertingEnabled);
        List<AwarenessItem> awareness = filterAwareness(activity.awareness(), complianceEnabled, directorySyncEnabled);

        return new UnifiedDashboardDto(
                complianceEnabled, hrEnabled, approvalsConfigured,
                metrics, alertSummary,
                aging, campaignProgress, directories, profiles, recentActivity,
                enabledReportJobs, failedReportJobs,
                actions, suggestions, awareness, firstDirectoryId);
    }

    // ── Source → unified converters ─────────────────────────────────────────

    private static ApprovalAgingDto mapAging(ComplianceDashboardDto.ApprovalAgingDto src) {
        if (src == null) return new ApprovalAgingDto(0, 0, 0, 0);
        return new ApprovalAgingDto(src.lessThan24h(), src.oneToThreeDays(), src.threeToSevenDays(), src.moreThanSevenDays());
    }

    private static ApprovalAgingDto mapAgingAdmin(AdminDashboardDto.ApprovalAgingDto src) {
        if (src == null) return new ApprovalAgingDto(0, 0, 0, 0);
        return new ApprovalAgingDto(src.lessThan24h(), src.oneToThreeDays(), src.threeToSevenDays(), src.moreThanSevenDays());
    }

    private static List<CampaignProgressDto> mapCampaigns(List<ComplianceDashboardDto.CampaignProgressDto> src) {
        if (src == null) return List.of();
        return src.stream().map(c -> new CampaignProgressDto(
                c.campaignId(), c.campaignName(), c.directoryName(),
                c.totalDecisions(), c.decidedCount(), c.completionPercent(),
                c.overdue(), c.deadline())).toList();
    }

    private static List<CampaignProgressDto> mapCampaignsAdmin(List<AdminDashboardDto.CampaignProgressDto> src) {
        if (src == null) return List.of();
        return src.stream().map(c -> new CampaignProgressDto(
                c.campaignId(), c.campaignName(), c.directoryName(),
                c.totalDecisions(), c.decidedCount(), c.completionPercent(),
                c.overdue(), c.deadline())).toList();
    }

    private static List<DirectoryStatDto> mapDirs(List<ComplianceDashboardDto.DirectoryStatDto> src) {
        if (src == null) return List.of();
        return src.stream().map(d -> new DirectoryStatDto(
                d.id(), d.name(), d.enabled(),
                d.userCount(), d.groupCount(),
                d.pendingApprovals(), d.activeCampaigns(), d.openSodViolations())).toList();
    }

    private static List<DirectoryStatDto> mapDirsAdmin(List<AdminDashboardDto.DirectoryStatDto> src) {
        if (src == null) return List.of();
        return src.stream().map(d -> new DirectoryStatDto(
                d.id(), d.name(), d.enabled(),
                d.userCount(), d.groupCount(),
                d.pendingApprovals(), d.activeCampaigns(), d.openSodViolations())).toList();
    }

    private static List<ProfileStatDto> mapProfilesAdmin(List<AdminDashboardDto.ProfileStatDto> src) {
        if (src == null) return List.of();
        return src.stream().map(p -> new ProfileStatDto(
                p.id(), p.name(),
                p.directoryId(), p.directoryName(),
                p.baseRole(), p.targetOuDn(),
                p.userCount(), p.groupCount(), p.pendingApprovals())).toList();
    }

    // ── Feature-flag filters ────────────────────────────────────────────────

    private static List<ActionItem> filterActions(List<ActivityDashboardResponse.ActionItem> src,
                                                   boolean complianceEnabled,
                                                   boolean directorySyncEnabled) {
        if (src == null) return List.of();
        return src.stream()
                .filter(a -> complianceEnabled || !COMPLIANCE_ACTION_TYPES.contains(a.type()))
                .filter(a -> directorySyncEnabled || !"REPLICATION_DEAD_LETTERED".equals(a.type()))
                .map(a -> new ActionItem(a.type(), a.severity(), a.title(), a.detail(), a.link(), a.count()))
                .toList();
    }

    private static List<SuggestedAction> filterSuggestions(List<ActivityDashboardResponse.SuggestedAction> src,
                                                            boolean complianceEnabled, boolean hrEnabled,
                                                            boolean alertingEnabled) {
        if (src == null) return List.of();
        return src.stream()
                .filter(s -> {
                    String key = s.key();
                    if (key == null) return true;
                    if (!complianceEnabled && (key.startsWith("sod-") || key.startsWith("campaign-"))) return false;
                    if (!hrEnabled && key.startsWith("hr-")) return false;
                    if (!alertingEnabled && key.startsWith("alerts-")) return false;
                    return true;
                })
                .map(s -> new SuggestedAction(s.key(), s.title(), s.description(), s.link(), s.icon()))
                .toList();
    }

    private static List<AwarenessItem> filterAwareness(List<ActivityDashboardResponse.AwarenessItem> src,
                                                        boolean complianceEnabled,
                                                        boolean directorySyncEnabled) {
        if (src == null) return List.of();
        return src.stream()
                .filter(a -> complianceEnabled || !COMPLIANCE_AWARENESS_TYPES.contains(a.type()))
                .filter(a -> directorySyncEnabled || !"REPLICATION_LAG_HIGH".equals(a.type()))
                .map(a -> new AwarenessItem(a.type(), a.title(), a.detail(), a.link()))
                .toList();
    }
}
