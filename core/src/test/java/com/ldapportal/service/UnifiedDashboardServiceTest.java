// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.dashboard.ActivityDashboardResponse;
import com.ldapportal.dto.dashboard.ActivityDashboardResponse.ActionItem;
import com.ldapportal.dto.dashboard.ActivityDashboardResponse.AwarenessItem;
import com.ldapportal.dto.dashboard.ActivityDashboardResponse.SuggestedAction;
import com.ldapportal.dto.dashboard.ActivityDashboardResponse.SummaryMetrics;
import com.ldapportal.dto.dashboard.AdminDashboardDto;
import com.ldapportal.dto.dashboard.ComplianceDashboardDto;
import com.ldapportal.dto.dashboard.UnifiedDashboardDto;
import com.ldapportal.core.dashboard.ReportJobHealth;
import com.ldapportal.core.dashboard.ReportJobHealthProvider;
import com.ldapportal.core.alerting.AlertSummary;
import com.ldapportal.core.alerting.AlertSummaryProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnifiedDashboardServiceTest {

    @Mock private DashboardService dashboardService;
    @Mock private AdminDashboardService adminDashboardService;
    @Mock private ActivityDashboardService activityDashboardService;
    @Mock private AlertSummaryProvider alertSummaryProvider;
    @Mock private ApplicationSettingsService settingsService;
    @Mock private ReportJobHealthProvider reportJobHealthProvider;
    @Mock private com.ldapportal.core.entitlement.EntitlementService entitlementService;

    private UnifiedDashboardService service;

    private final AuthPrincipal superadmin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "root");
    private final AuthPrincipal admin      = new AuthPrincipal(PrincipalType.ADMIN,      UUID.randomUUID(), "alice");

    @BeforeEach
    void setUp() {
        service = new UnifiedDashboardService(dashboardService, adminDashboardService,
                activityDashboardService, alertSummaryProvider, settingsService, reportJobHealthProvider,
                entitlementService);

        when(alertSummaryProvider.summary()).thenReturn(new AlertSummary(3, 0, 1, 2, 0, 0));
        when(reportJobHealthProvider.health()).thenReturn(new ReportJobHealth(4L, 1L));
    }

    // ── Superadmin dispatch ─────────────────────────────────────────────────

    @Test
    void superadmin_compliance_on_uses_dashboardService_and_keeps_all_content() {
        stubSettings(true, true);
        when(dashboardService.getDashboard()).thenReturn(sampleComplianceDto());
        when(activityDashboardService.build(superadmin)).thenReturn(sampleActivity());

        UnifiedDashboardDto out = service.getDashboard(superadmin);

        // Dispatched to DashboardService, not AdminDashboardService
        verify(dashboardService).getDashboard();
        verifyNoInteractions(adminDashboardService);

        assertThat(out.complianceEnabled()).isTrue();
        assertThat(out.hrIntegrationEnabled()).isTrue();

        // Metrics preserved verbatim
        assertThat(out.metrics().openSodViolations()).isEqualTo(7);
        assertThat(out.metrics().overdueCampaigns()).isEqualTo(2);
        assertThat(out.metrics().campaignCompletionPercent()).isEqualTo(42.0);
        assertThat(out.campaignProgress()).hasSize(1);

        // Nothing gets filtered out
        assertThat(out.actions()).extracting(UnifiedDashboardDto.ActionItem::type)
                .containsExactly("APPROVAL", "REVIEW", "CAMPAIGN_OVERDUE", "ALERT");
        assertThat(out.suggestions()).extracting(UnifiedDashboardDto.SuggestedAction::key)
                .containsExactly("sod-abc", "campaign-abc", "hr-abc", "smtp");
        assertThat(out.awareness()).extracting(UnifiedDashboardDto.AwarenessItem::type)
                .containsExactly("RECENT_CHANGES", "UPCOMING_DEADLINE", "SYNC_STATUS");

        // Superadmin payload has no firstDirectoryId
        assertThat(out.firstDirectoryId()).isNull();

        // Report-job counters come through from the shared path
        assertThat(out.enabledReportJobs()).isEqualTo(4L);
        assertThat(out.failedReportJobs()).isEqualTo(1L);
    }

    @Test
    void superadmin_compliance_off_zeros_metrics_and_drops_campaign_panels_and_filters_activity() {
        stubSettings(false, true);
        when(dashboardService.getDashboard()).thenReturn(sampleComplianceDto());
        when(activityDashboardService.build(superadmin)).thenReturn(sampleActivity());

        UnifiedDashboardDto out = service.getDashboard(superadmin);

        assertThat(out.complianceEnabled()).isFalse();

        // Compliance-only fields zeroed out
        assertThat(out.metrics().openSodViolations()).isZero();
        assertThat(out.metrics().overdueCampaigns()).isZero();
        assertThat(out.metrics().campaignCompletionPercent()).isNull();
        assertThat(out.metrics().activeCampaigns()).isZero();
        // Non-compliance metrics still present
        assertThat(out.metrics().totalUsers()).isEqualTo(100L);
        assertThat(out.metrics().totalGroups()).isEqualTo(20L);
        assertThat(out.metrics().pendingApprovals()).isEqualTo(5L);

        // Campaign progress list emptied
        assertThat(out.campaignProgress()).isEmpty();

        // Compliance action types filtered
        assertThat(out.actions()).extracting(UnifiedDashboardDto.ActionItem::type)
                .containsExactly("APPROVAL", "ALERT");

        // Compliance-prefixed suggestions filtered (sod-*, campaign-*)
        assertThat(out.suggestions()).extracting(UnifiedDashboardDto.SuggestedAction::key)
                .containsExactly("hr-abc", "smtp");

        // Compliance awareness type filtered
        assertThat(out.awareness()).extracting(UnifiedDashboardDto.AwarenessItem::type)
                .containsExactly("RECENT_CHANGES", "SYNC_STATUS");
    }

    @Test
    void hr_disabled_filters_hr_prefixed_suggestions() {
        stubSettings(true, false);
        when(dashboardService.getDashboard()).thenReturn(sampleComplianceDto());
        when(activityDashboardService.build(superadmin)).thenReturn(sampleActivity());

        UnifiedDashboardDto out = service.getDashboard(superadmin);

        assertThat(out.hrIntegrationEnabled()).isFalse();
        assertThat(out.suggestions()).extracting(UnifiedDashboardDto.SuggestedAction::key)
                .containsExactly("sod-abc", "campaign-abc", "smtp")  // hr-abc dropped
                .doesNotContain("hr-abc");
    }

    // ── Admin dispatch ──────────────────────────────────────────────────────

    @Test
    void admin_dispatches_to_admin_dashboard_service_and_populates_firstDirectoryId() {
        stubSettings(true, true);
        when(adminDashboardService.getDashboard(admin)).thenReturn(sampleAdminDto());
        when(activityDashboardService.build(admin)).thenReturn(sampleActivity());

        UnifiedDashboardDto out = service.getDashboard(admin);

        verify(adminDashboardService).getDashboard(admin);
        verifyNoInteractions(dashboardService);

        assertThat(out.firstDirectoryId()).isEqualTo("dir-first");
        assertThat(out.metrics().totalUsers()).isEqualTo(50L);
        assertThat(out.metrics().activeCampaigns()).isEqualTo(2L);
    }

    @Test
    void admin_compliance_off_applies_same_filtering_as_superadmin() {
        stubSettings(false, true);
        when(adminDashboardService.getDashboard(admin)).thenReturn(sampleAdminDto());
        when(activityDashboardService.build(admin)).thenReturn(sampleActivity());

        UnifiedDashboardDto out = service.getDashboard(admin);

        assertThat(out.metrics().openSodViolations()).isZero();
        assertThat(out.campaignProgress()).isEmpty();
        assertThat(out.actions()).extracting(UnifiedDashboardDto.ActionItem::type)
                .doesNotContain("REVIEW", "CAMPAIGN_OVERDUE");
        assertThat(out.awareness()).extracting(UnifiedDashboardDto.AwarenessItem::type)
                .doesNotContain("UPCOMING_DEADLINE");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void stubSettings(boolean compliance, boolean hr) {
        // UnifiedDashboardService consults entitlementService for these two
        // flags (Phase 1 of the packaging refactor). The backing settings
        // row is no longer read from this service's code path, so we only
        // stub the entitlement side here.
        when(entitlementService.has(com.ldapportal.core.entitlement.Entitlement.GOVERNANCE))
                .thenReturn(compliance);
        when(entitlementService.has(com.ldapportal.core.entitlement.Entitlement.HR_SYNC))
                .thenReturn(hr);
    }

    private ComplianceDashboardDto sampleComplianceDto() {
        var aging = new ComplianceDashboardDto.ApprovalAgingDto(1, 2, 3, 4);
        var progress = List.of(new ComplianceDashboardDto.CampaignProgressDto(
                "c1", "Q1 Review", "Corp LDAP", 10, 4, 40.0, false, "2026-05-01"));
        var dirs = List.of(new ComplianceDashboardDto.DirectoryStatDto(
                "dir-1", "Corp LDAP", true, 100, 20, 5, 1, 7));
        return new ComplianceDashboardDto(
                100, 20, 5,
                7, 42.0, 2, 0,
                aging, true,
                progress, dirs, List.of(),
                4, 1);
    }

    private AdminDashboardDto sampleAdminDto() {
        var aging = new AdminDashboardDto.ApprovalAgingDto(0, 0, 0, 0);
        var progress = List.of(new AdminDashboardDto.CampaignProgressDto(
                "c2", "Q2 Review", "Corp LDAP", 5, 1, 20.0, false, "2026-05-10"));
        var dirs = List.of(new AdminDashboardDto.DirectoryStatDto(
                "dir-first", "Corp LDAP", true, 50, 10, 2, 2, 3));
        var profiles = List.of(new AdminDashboardDto.ProfileStatDto(
                "prof-a", "Engineering", "dir-first", "Corp LDAP",
                "ADMIN", "ou=eng,dc=example,dc=com",
                42, 8, 2));
        return new AdminDashboardDto(
                50, 10, 2,
                3, 2, 20.0, 0,
                aging, true,
                progress, dirs, profiles, List.of(), "dir-first");
    }

    private ActivityDashboardResponse sampleActivity() {
        List<ActionItem> actions = List.of(
                new ActionItem("APPROVAL",         "HIGH",     "2 approvals pending",       null, "/approvals", 2),
                new ActionItem("REVIEW",           "MEDIUM",   "Review due",                null, "/reviews",   1),
                new ActionItem("CAMPAIGN_OVERDUE", "HIGH",     "Campaign overdue",          null, "/campaigns", 1),
                new ActionItem("ALERT",            "CRITICAL", "Critical alert",            null, "/alerts",    1)
        );
        List<SuggestedAction> suggestions = List.of(
                new SuggestedAction("sod-abc",      "Set up SoD",  "", "/s", "policy"),
                new SuggestedAction("campaign-abc", "New campaign", "", "/c", "review"),
                new SuggestedAction("hr-abc",       "Connect HR",  "", "/h", "sync"),
                new SuggestedAction("smtp",         "Configure SMTP", "", "/m", "setup")
        );
        List<AwarenessItem> awareness = List.of(
                new AwarenessItem("RECENT_CHANGES",    "14 changes", null, "/audit"),
                new AwarenessItem("UPCOMING_DEADLINE", "Review due soon", null, "/reviews"),
                new AwarenessItem("SYNC_STATUS",       "Synced 4h ago", null, "/entra")
        );
        SummaryMetrics metrics = new SummaryMetrics(100, 20, 7, 3, 1);
        return new ActivityDashboardResponse(actions, suggestions, awareness, metrics);
    }
}
