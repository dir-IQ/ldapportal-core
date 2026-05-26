// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.dashboard;

import com.ldapportal.dto.audit.AuditEventResponse;

import java.util.List;

/**
 * Structured compliance posture dashboard response.
 */
public record ComplianceDashboardDto(
        // ── Summary totals ────────────────────────────────────────────────────
        long totalUsers,
        long totalGroups,
        long totalPendingApprovals,

        // ── Compliance posture metrics ────────────────────────────────────────
        long openSodViolations,
        /** Null when there are no active campaigns (avoids misleading "100%"). */
        Double campaignCompletionPercent,
        long overdueCampaigns,
        long usersNotReviewedIn90Days,

        // ── Approval aging buckets ────────────────────────────────────────────
        ApprovalAgingDto approvalAging,
        /** True when any profile has {@code requireApproval = true}.
         *  See UnifiedDashboardDto. */
        boolean approvalsConfigured,

        // ── Active campaign progress ──────────────────────────────────────────
        List<CampaignProgressDto> campaignProgress,

        // ── Per-directory breakdown ───────────────────────────────────────────
        List<DirectoryStatDto> directories,

        // ── Recent audit events ───────────────────────────────────────────────
        List<AuditEventResponse> recentAudit,

        // ── Scheduled report job status ───────────────────────────────────────
        long enabledReportJobs,
        long failedReportJobs
) {

    public record ApprovalAgingDto(
            long lessThan24h,
            long oneToThreeDays,
            long threeToSevenDays,
            long moreThanSevenDays
    ) {
        public long total() {
            return lessThan24h + oneToThreeDays + threeToSevenDays + moreThanSevenDays;
        }
    }

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
}
