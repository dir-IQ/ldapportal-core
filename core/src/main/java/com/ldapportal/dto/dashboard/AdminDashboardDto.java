// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.dashboard;

import com.ldapportal.dto.audit.AuditEventResponse;

import java.util.List;

/**
 * Dashboard response scoped to the admin's authorized directories.
 */
public record AdminDashboardDto(

        // ── Summary totals (across authorized directories) ──────────────────
        long totalUsers,
        long totalGroups,
        long totalPendingApprovals,

        // ── Compliance indicators ───────────────────────────────────────────
        long openSodViolations,
        long activeAccessReviewCampaigns,
        Double campaignCompletionPercent,
        long overdueCampaigns,

        // ── Approval aging ──────────────────────────────────────────────────
        ApprovalAgingDto approvalAging,
        /** True when any profile in the admin's authorized directories
         *  has {@code requireApproval = true}. See UnifiedDashboardDto. */
        boolean approvalsConfigured,

        // ── Active campaign progress ────────────────────────────────────────
        List<CampaignProgressDto> campaignProgress,

        // ── Per-directory breakdown ─────────────────────────────────────────
        List<DirectoryStatDto> directories,

        // ── Per-profile breakdown (admin view) ──────────────────────────────
        // Admins think in profiles (their actual grants) rather than raw
        // directories — a directory can host several profiles with
        // different base roles and target OUs. The dashboard renders this
        // list as the Profiles panel in place of Directories for admins.
        List<ProfileStatDto> profiles,

        // ── Recent activity (across authorized directories) ─────────────────
        List<AuditEventResponse> recentActivity,

        // ── Quick-action context ────────────────────────────────────────────
        String firstDirectoryId
) {

    /** Re-use the same nested records from ComplianceDashboardDto. */
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

    /**
     * Per-profile stats row for the admin dashboard.
     *
     * <p>{@code userCount}/{@code groupCount} are LDAP counts scoped to
     * the profile's {@code targetOuDn} — not the whole directory — so the
     * number reflects what the admin can actually see and act on under
     * this profile. Multiple profiles sharing the same (directory,
     * target OU) get the same count (the service dedupes the LDAP query).
     * A value of {@code -1} means the LDAP count failed; the UI renders
     * this as an em-dash.</p>
     */
    public record ProfileStatDto(
            String id,
            String name,
            String directoryId,
            String directoryName,
            String baseRole,
            String targetOuDn,
            long userCount,
            long groupCount,
            long pendingApprovals
    ) {}
}
