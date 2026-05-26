// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.governance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * SPI for all governance-derived dashboard data — access-review campaigns,
 * SoD violations, and the user-review staleness computation. Implemented
 * by {@code ee.governance.service.GovernanceDashboardService}; core
 * ships {@link NoopGovernanceDashboardProvider} as a zero-valued default
 * so dashboards render cleanly in the community edition where no
 * governance module is loaded.
 *
 * <p>Consumers are the three core dashboard services (superadmin
 * compliance, per-admin, and activity). Each of them used to reach
 * directly into {@code ee.governance.repository} and
 * {@code ee.governance.entity} — this SPI inverts that dependency so
 * {@code ..core..} doesn't import {@code ..ee..}.</p>
 *
 * <p>Methods return denormalised shapes (directory names already
 * resolved, deadline formatted as ISO-8601 string) so the dashboard
 * services don't need access to the underlying JPA entities.</p>
 */
public interface GovernanceDashboardProvider {

    /** Per-directory counts surfaced in DirectoryStatDto rows. */
    record DirectoryGovernanceCounts(long activeCampaigns, long openSodViolations) {
        public static DirectoryGovernanceCounts empty() {
            return new DirectoryGovernanceCounts(0L, 0L);
        }
    }

    /** One row in the "campaign progress" dashboard panel. */
    record CampaignProgressRow(
            String campaignId,
            String campaignName,
            String directoryName,
            UUID directoryId,
            long totalDecisions,
            long decidedCount,
            double completionPercent,
            boolean overdue,
            String deadline) {
    }

    /** A prompt on the activity dashboard for pending review decisions. */
    record PendingDecisionPrompt(
            UUID directoryId,
            String campaignId,
            String campaignName,
            long pendingDecisions,
            String deadlineSummary) {
    }

    /** An overdue active campaign for the activity dashboard action list. */
    record OverdueCampaign(
            UUID directoryId,
            String campaignId,
            String campaignName,
            long daysOverdue) {
    }

    /** A campaign whose deadline is approaching. */
    record UpcomingDeadline(
            UUID directoryId,
            String campaignId,
            String campaignName,
            long daysUntil) {
    }

    // ── Per-directory counts ────────────────────────────────────────────────

    /** Counts for a single directory. Returns zeros if unknown. */
    DirectoryGovernanceCounts directoryCounts(UUID directoryId);

    // ── Campaign progress ───────────────────────────────────────────────────

    /** All active campaigns (used by the superadmin compliance dashboard). */
    List<CampaignProgressRow> activeCampaignProgress();

    /** Active campaigns scoped to the given directories (admin dashboard). */
    List<CampaignProgressRow> activeCampaignProgress(Set<UUID> directoryIds);

    // ── Global aggregates ───────────────────────────────────────────────────

    /** Total count of OPEN SoD violations across all directories. */
    long totalOpenSodViolations();

    /** Count of active campaigns whose deadline has already passed. */
    long overdueCampaignsCount();

    // ── Staleness computation ───────────────────────────────────────────────

    /**
     * Count of distinct users in the directory who received any access-review
     * decision since the given timestamp. Used to compute "users not reviewed
     * in the last N days".
     */
    long reviewedUsersSince(UUID directoryId, OffsetDateTime since);

    // ── Activity-dashboard shapes ───────────────────────────────────────────

    /** Pending-decision prompts for review campaigns in the given scope. */
    List<PendingDecisionPrompt> pendingDecisionPromptsForDirectories(Set<UUID> directoryIds);

    /** Active + overdue campaigns in the given scope. */
    List<OverdueCampaign> overdueCampaignsForDirectories(Set<UUID> directoryIds);

    /**
     * Active campaigns with deadlines falling inside the given window from now.
     * The activity dashboard uses this to surface "upcoming deadlines".
     */
    List<UpcomingDeadline> upcomingDeadlinesForDirectories(Set<UUID> directoryIds, long withinDays);

    /** True if the directory has ever had any campaign (open or closed). */
    boolean hasAnyCampaignHistory(UUID directoryId);
}
