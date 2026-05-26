// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.core.governance.GovernanceDashboardProvider;
import com.ldapportal.dto.audit.AuditEventResponse;
import com.ldapportal.dto.dashboard.ComplianceDashboardDto;
import com.ldapportal.dto.dashboard.ComplianceDashboardDto.*;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.enums.ApprovalStatus;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Builds the compliance posture dashboard.
 *
 * <p>Results are cached for 60 seconds to avoid repeated expensive LDAP queries
 * and DB aggregations on every page load.</p>
 *
 * <p>Governance-derived data (SoD violations, campaigns, decisions) comes
 * in through {@link GovernanceDashboardProvider} so this class doesn't
 * depend on {@code ee.governance} — community builds pick up the no-op
 * default and the compliance panels render as zeros.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DashboardService {

    private final DirectoryConnectionRepository dirRepo;
    private final PendingApprovalRepository approvalRepo;
    private final AuditQueryService auditQueryService;
    private final LdapUserService userService;
    private final LdapGroupService groupService;
    private final ProfileApprovalConfigRepository approvalConfigRepo;
    private final GovernanceDashboardProvider governance;
    private final com.ldapportal.core.dashboard.ReportJobHealthProvider reportJobHealthProvider;

    /** User-class filter portable across OpenLDAP and AD. */
    private static final String USER_OBJECTCLASS_FILTER =
            "(|(objectClass=inetOrgPerson)(&(objectClass=user)(!(objectClass=computer))))";

    private static final String GROUP_OBJECTCLASS_FILTER =
            "(|(objectClass=groupOfNames)(objectClass=groupOfUniqueNames)(objectClass=posixGroup)(objectClass=group)(objectClass=groupOfURLs))";

    /** Maximum entries to load for counting. */
    private static final int MAX_COUNT = 100_000;

    // ── Cache ────────────────────────────────────────────────────────────────
    private volatile ComplianceDashboardDto cachedDashboard;
    private volatile long cacheTimestamp;
    private static final long CACHE_TTL_MS = 60_000;

    /** Force-invalidate the cache (e.g., after a significant change). */
    public void invalidateCache() {
        cachedDashboard = null;
    }

    @Transactional(readOnly = true)
    public ComplianceDashboardDto getDashboard() {
        long now = System.currentTimeMillis();
        if (cachedDashboard != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedDashboard;
        }

        ComplianceDashboardDto result = buildDashboard();
        cachedDashboard = result;
        cacheTimestamp = System.currentTimeMillis();
        return result;
    }

    private ComplianceDashboardDto buildDashboard() {
        List<DirectoryConnection> dirs = dirRepo.findAll();
        OffsetDateTime now = OffsetDateTime.now();

        // ── Per-directory stats ──────────────────────────────────────────────
        List<DirectoryStatDto> dirStats = new ArrayList<>();
        long totalUsers = 0;
        long totalGroups = 0;
        long totalPending = 0;

        for (DirectoryConnection dc : dirs) {
            long userCount = 0;
            long groupCount = 0;

            if (dc.isEnabled()) {
                try {
                    userCount = userService.searchUsers(dc, USER_OBJECTCLASS_FILTER, null, MAX_COUNT, "1.1").size();
                    if (userCount >= MAX_COUNT) {
                        log.warn("User count for '{}' hit the {} limit — actual count may be higher",
                                dc.getDisplayName(), MAX_COUNT);
                    }
                } catch (Exception e) {
                    log.warn("Failed to count users for directory {}: {}", dc.getDisplayName(), e.getMessage());
                    userCount = -1;
                }
                try {
                    groupCount = groupService.searchGroups(dc, GROUP_OBJECTCLASS_FILTER, null, MAX_COUNT, "1.1").size();
                } catch (Exception e) {
                    log.warn("Failed to count groups for directory {}: {}", dc.getDisplayName(), e.getMessage());
                    groupCount = -1;
                }
            }

            long pending = approvalRepo.countByDirectoryIdAndStatus(dc.getId(), ApprovalStatus.PENDING);
            GovernanceDashboardProvider.DirectoryGovernanceCounts gov = governance.directoryCounts(dc.getId());

            if (userCount >= 0) totalUsers += userCount;
            if (groupCount >= 0) totalGroups += groupCount;
            totalPending += pending;

            dirStats.add(new DirectoryStatDto(
                    dc.getId().toString(), dc.getDisplayName(), dc.isEnabled(),
                    userCount, groupCount, pending, gov.activeCampaigns(), gov.openSodViolations()));
        }

        // ── Governance aggregates via SPI ────────────────────────────────────
        long openSodViolations = governance.totalOpenSodViolations();
        List<GovernanceDashboardProvider.CampaignProgressRow> campaignRows = governance.activeCampaignProgress();
        List<CampaignProgressDto> campaignProgress = new ArrayList<>(campaignRows.size());
        long globalTotalDecisions = 0;
        long globalDecided = 0;
        for (GovernanceDashboardProvider.CampaignProgressRow r : campaignRows) {
            globalTotalDecisions += r.totalDecisions();
            globalDecided += r.decidedCount();
            campaignProgress.add(new CampaignProgressDto(
                    r.campaignId(), r.campaignName(), r.directoryName(),
                    r.totalDecisions(), r.decidedCount(), r.completionPercent(),
                    r.overdue(), r.deadline()));
        }

        // Null when no campaigns exist (avoids misleading "100%")
        Double campaignCompletionPct = campaignRows.isEmpty() ? null
                : globalTotalDecisions > 0
                    ? Math.round((globalDecided * 100.0 / globalTotalDecisions) * 10) / 10.0
                    : 0.0;

        long overdueCampaigns = governance.overdueCampaignsCount();

        // ── Approval aging buckets ───────────────────────────────────────────
        ApprovalAgingDto approvalAging = computeApprovalAging(now);

        // ── Approval feature configured anywhere? ────────────────────────────
        // Drives frontend gating of approval-related UI. Independent of the
        // current pending-queue depth — an env with approvals enabled but
        // a quiet queue still shows "0 pending" as an "inbox clear" signal.
        boolean approvalsConfigured = approvalConfigRepo.existsByRequireApprovalTrue();

        // ── Users not reviewed in 90 days ────────────────────────────────────
        long usersNotReviewedIn90Days = computeUsersNotReviewedIn90Days(dirs, now);

        // ── Recent audit events ──────────────────────────────────────────────
        var recentAudit = auditQueryService.query(null, null, null, null, null, 0, 10);

        // ── Scheduled report job stats ────────────────────────────────────────
        var reportJobs = reportJobHealthProvider.health();

        return new ComplianceDashboardDto(
                totalUsers, totalGroups, totalPending,
                openSodViolations, campaignCompletionPct, overdueCampaigns,
                usersNotReviewedIn90Days,
                approvalAging, approvalsConfigured,
                campaignProgress, dirStats,
                recentAudit.getContent(),
                reportJobs.enabled(), reportJobs.failed());
    }

    private ApprovalAgingDto computeApprovalAging(OffsetDateTime now) {
        List<PendingApproval> pendingApprovals = approvalRepo.findAllByStatus(ApprovalStatus.PENDING);

        long lt24h = 0, d1to3 = 0, d3to7 = 0, gt7d = 0;
        for (PendingApproval pa : pendingApprovals) {
            Duration age = Duration.between(pa.getCreatedAt(), now);
            long hours = age.toHours();
            if (hours < 24) lt24h++;
            else if (hours < 72) d1to3++;
            else if (hours < 168) d3to7++;
            else gt7d++;
        }
        return new ApprovalAgingDto(lt24h, d1to3, d3to7, gt7d);
    }

    /**
     * Computes users not reviewed in 90 days using per-directory calculation
     * to avoid cross-directory counting errors.
     */
    private long computeUsersNotReviewedIn90Days(List<DirectoryConnection> dirs, OffsetDateTime now) {
        OffsetDateTime ninetyDaysAgo = now.minusDays(90);
        long totalUnreviewed = 0;

        for (DirectoryConnection dc : dirs) {
            if (!dc.isEnabled()) continue;

            // Get user count for this specific directory
            long dirUserCount = 0;
            try {
                dirUserCount = userService.searchUsers(dc, USER_OBJECTCLASS_FILTER, null, MAX_COUNT, "1.1").size();
            } catch (Exception e) {
                continue; // skip directories we can't query
            }

            try {
                long reviewed = governance.reviewedUsersSince(dc.getId(), ninetyDaysAgo);
                totalUnreviewed += Math.max(0, dirUserCount - reviewed);
            } catch (Exception e) {
                log.warn("Failed to count reviewed users for directory {}: {}", dc.getDisplayName(), e.getMessage());
                totalUnreviewed += dirUserCount; // assume none reviewed on error
            }
        }
        return totalUnreviewed;
    }
}
