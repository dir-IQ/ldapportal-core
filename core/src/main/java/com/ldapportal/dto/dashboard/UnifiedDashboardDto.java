// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.dashboard;

import com.ldapportal.core.alerting.AlertSummary;
import com.ldapportal.dto.audit.AuditEventResponse;

import java.util.List;

/**
 * Single aggregated payload for the unified dashboard view. Composes the outputs
 * of the compliance/admin dashboard services, the activity dashboard service,
 * the alert-summary service, and scheduled-report-job counters, with server-side
 * filtering applied based on {@code complianceEnabled} / {@code hrIntegrationEnabled}.
 */
public record UnifiedDashboardDto(
        boolean complianceEnabled,
        boolean hrIntegrationEnabled,
        /**
         * True when at least one profile visible to the caller has
         * {@code requireApproval = true}. Drives whether approval-related
         * UI (Pending Approvals metric card, Approval Aging panel) renders
         * on the dashboard — an environment that doesn't use approval
         * workflows shouldn't carry empty approval tiles. Frontend also
         * ORs this with {@code metrics.pendingApprovals > 0} so residual
         * pending items from a previously-enabled workflow stay visible
         * until drained.
         */
        boolean approvalsConfigured,

        MetricsDto metrics,
        AlertSummary alertSummary,

        ApprovalAgingDto approvalAging,
        List<CampaignProgressDto> campaignProgress,
        List<DirectoryStatDto> directories,
        /** Per-profile breakdown for admin callers; empty for superadmins
         *  (who see Directories instead). Frontend picks the panel based
         *  on which list is non-empty. */
        List<ProfileStatDto> profiles,
        List<AuditEventResponse> recentActivity,

        long enabledReportJobs,
        long failedReportJobs,

        List<ActionItem> actions,
        List<SuggestedAction> suggestions,
        List<AwarenessItem> awareness,

        /** Admin-only convenience — the first authorized directory ID for deep-link nav. */
        String firstDirectoryId
) {

    public record MetricsDto(
            long totalUsers,
            long totalGroups,
            long pendingApprovals,
            long openSodViolations,
            long activeCampaigns,
            /** Null when there are no active campaigns (avoids misleading "100%"). */
            Double campaignCompletionPercent,
            long overdueCampaigns
    ) {}

    public record ApprovalAgingDto(
            long lessThan24h,
            long oneToThreeDays,
            long threeToSevenDays,
            long moreThanSevenDays
    ) {}

    public record CampaignProgressDto(
            String campaignId,
            String campaignName,
            String directoryName,
            long totalDecisions,
            long decidedCount,
            double completionPercent,
            boolean overdue,
            String deadline
    ) {}

    public record DirectoryStatDto(
            String id,
            String name,
            boolean enabled,
            long userCount,
            long groupCount,
            long pendingApprovals,
            long activeCampaigns,
            long openSodViolations
    ) {}

    public record ProfileStatDto(
            String id,
            String name,
            String directoryId,
            String directoryName,
            String baseRole,
            String targetOuDn,
            /** LDAP user count scoped to {@code targetOuDn}. -1 = failure. */
            long userCount,
            /** LDAP group count scoped to {@code targetOuDn}. -1 = failure. */
            long groupCount,
            long pendingApprovals
    ) {}

    public record ActionItem(
            String type,
            String severity,
            String title,
            String detail,
            String link,
            int count
    ) {}

    public record SuggestedAction(
            String key,
            String title,
            String description,
            String link,
            String icon
    ) {}

    public record AwarenessItem(
            String type,
            String title,
            String detail,
            String link
    ) {}
}
