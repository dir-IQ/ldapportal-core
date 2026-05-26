// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.dashboard;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Activity-based home screen response.
 */
public record ActivityDashboardResponse(
        // ── Priority 1: Action Required ──────────────────────────────────────
        List<ActionItem> actions,

        // ── Priority 2: Suggested Configuration ─────────────────────────────
        List<SuggestedAction> suggestions,

        // ── Priority 3: Awareness ────────────────────────────────────────────
        List<AwarenessItem> awareness,

        // ── Priority 4: Summary Metrics ──────────────────────────────────────
        SummaryMetrics metrics
) {

    public record ActionItem(
            String type,        // APPROVAL, REVIEW, ALERT, CAMPAIGN_OVERDUE
            String severity,    // CRITICAL, HIGH, MEDIUM, LOW
            String title,
            String detail,
            String link,
            int count
    ) {}

    public record SuggestedAction(
            String key,         // unique key for dismissal
            String title,
            String description,
            String link,
            String icon         // setup, policy, review, sync, alert, report
    ) {}

    public record AwarenessItem(
            String type,        // RECENT_CHANGES, UPCOMING_DEADLINE, SYNC_STATUS, STALE_APPROVAL
            String title,
            String detail,
            String link
    ) {}

    public record SummaryMetrics(
            long totalUsers,
            long totalGroups,
            long openSodViolations,
            long openAlerts,
            long activeReviewCampaigns
    ) {}
}
