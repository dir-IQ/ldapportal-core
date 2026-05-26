// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.governance;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Default {@link GovernanceDashboardProvider} used when no governance
 * module is loaded. All methods return empty collections or zero
 * counts, so the compliance/admin/activity dashboards render cleanly
 * without the governance UI surfaces.
 *
 * <p>Registered via {@link com.ldapportal.core.CoreNoopSpiAutoConfiguration}
 * when no other {@link GovernanceDashboardProvider} bean is present —
 * community edition picks this up, commercial picks up the ee.governance
 * implementation.</p>
 */
public class NoopGovernanceDashboardProvider implements GovernanceDashboardProvider {

    @Override
    public DirectoryGovernanceCounts directoryCounts(UUID directoryId) {
        return DirectoryGovernanceCounts.empty();
    }

    @Override
    public List<CampaignProgressRow> activeCampaignProgress() {
        return List.of();
    }

    @Override
    public List<CampaignProgressRow> activeCampaignProgress(Set<UUID> directoryIds) {
        return List.of();
    }

    @Override
    public long totalOpenSodViolations() {
        return 0L;
    }

    @Override
    public long overdueCampaignsCount() {
        return 0L;
    }

    @Override
    public long reviewedUsersSince(UUID directoryId, OffsetDateTime since) {
        return 0L;
    }

    @Override
    public List<PendingDecisionPrompt> pendingDecisionPromptsForDirectories(Set<UUID> directoryIds) {
        return List.of();
    }

    @Override
    public List<OverdueCampaign> overdueCampaignsForDirectories(Set<UUID> directoryIds) {
        return List.of();
    }

    @Override
    public List<UpcomingDeadline> upcomingDeadlinesForDirectories(Set<UUID> directoryIds, long withinDays) {
        return List.of();
    }

    @Override
    public boolean hasAnyCampaignHistory(UUID directoryId) {
        return false;
    }
}
