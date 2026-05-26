// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.core.governance.GovernanceDashboardProvider;
import com.ldapportal.dto.audit.AuditEventResponse;
import com.ldapportal.dto.dashboard.AdminDashboardDto;
import com.ldapportal.dto.dashboard.AdminDashboardDto.*;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.enums.ApprovalStatus;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Builds the admin dashboard, scoped to the admin's authorized directories.
 *
 * <p>Governance-derived numbers (SoD violations, campaigns, decisions)
 * are sourced via {@link GovernanceDashboardProvider} so the admin
 * dashboard renders cleanly in community edition with the governance
 * panels showing zeros — while commercial builds get the full picture
 * through the ee.governance implementation.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AdminDashboardService {

    private final DirectoryConnectionRepository dirRepo;
    private final PendingApprovalRepository approvalRepo;
    private final AuditQueryService auditQueryService;
    private final LdapUserService userService;
    private final LdapGroupService groupService;
    private final PermissionService permissionService;
    private final ProfileApprovalConfigRepository approvalConfigRepo;
    private final AdminProfileRoleRepository profileRoleRepo;
    private final GovernanceDashboardProvider governance;

    private static final String USER_OBJECTCLASS_FILTER =
            "(|(objectClass=inetOrgPerson)(&(objectClass=user)(!(objectClass=computer))))";

    private static final String GROUP_OBJECTCLASS_FILTER =
            "(|(objectClass=groupOfNames)(objectClass=groupOfUniqueNames)(objectClass=posixGroup)(objectClass=group)(objectClass=groupOfURLs))";

    private static final int MAX_COUNT = 100_000;

    @Transactional(readOnly = true)
    public AdminDashboardDto getDashboard(AuthPrincipal principal) {
        Set<UUID> authorizedDirIds = permissionService.getAuthorizedDirectoryIds(principal);

        if (authorizedDirIds.isEmpty()) {
            return emptyDashboard();
        }

        List<DirectoryConnection> dirs = dirRepo.findAllById(authorizedDirIds);
        OffsetDateTime now = OffsetDateTime.now();

        // ── Per-directory stats ──────────────────────────────────────────────
        // Admin dashboard renders per-profile rows (see ProfileStatDto below),
        // not per-directory — so the user/group LDAP counts here are only
        // carried in dirStats for API completeness. The aggregate
        // totalUsers/totalGroups metric cards are recomputed below from the
        // per-profile counts instead, because directory-level totals overstate
        // what the admin can actually act on under their profiles' target OUs.
        List<DirectoryStatDto> dirStats = new ArrayList<>();
        long totalPending = 0;
        long totalActiveCampaigns = 0;
        long totalSodViolations = 0;

        for (DirectoryConnection dc : dirs) {
            long userCount = 0;
            long groupCount = 0;

            if (dc.isEnabled()) {
                try {
                    userCount = userService.searchUsers(dc, USER_OBJECTCLASS_FILTER, null, MAX_COUNT, "1.1").size();
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

            totalPending += pending;
            totalActiveCampaigns += gov.activeCampaigns();
            totalSodViolations += gov.openSodViolations();

            dirStats.add(new DirectoryStatDto(
                    dc.getId().toString(), dc.getDisplayName(), dc.isEnabled(),
                    userCount, groupCount, pending, gov.activeCampaigns(), gov.openSodViolations()));
        }

        // ── Campaign completion ──────────────────────────────────────────────
        List<GovernanceDashboardProvider.CampaignProgressRow> campaignRows =
                governance.activeCampaignProgress(authorizedDirIds);
        List<CampaignProgressDto> campaignProgress = new ArrayList<>(campaignRows.size());
        long globalTotalDecisions = 0;
        long globalDecided = 0;
        long overdueCampaigns = 0;

        for (GovernanceDashboardProvider.CampaignProgressRow r : campaignRows) {
            globalTotalDecisions += r.totalDecisions();
            globalDecided += r.decidedCount();
            if (r.overdue()) overdueCampaigns++;

            campaignProgress.add(new CampaignProgressDto(
                    r.campaignId(), r.campaignName(), r.directoryName(),
                    r.totalDecisions(), r.decidedCount(), r.completionPercent(),
                    r.overdue(), r.deadline()));
        }

        Double campaignCompletionPct = campaignProgress.isEmpty() ? null
                : globalTotalDecisions > 0
                    ? Math.round((globalDecided * 100.0 / globalTotalDecisions) * 10) / 10.0
                    : 0.0;

        // ── Approval aging ───────────────────────────────────────────────────
        ApprovalAgingDto approvalAging = computeApprovalAging(authorizedDirIds, now);

        // ── Approval feature configured in any authorized directory? ─────────
        // Scoped to the admin's authorized set so an admin doesn't see
        // approval UI just because a directory they can't access uses it.
        boolean approvalsConfigured = approvalConfigRepo
                .existsByRequireApprovalTrueAndProfile_Directory_IdIn(authorizedDirIds);

        // ── Per-profile stats ────────────────────────────────────────────────
        // One row per AdminProfileRole the caller holds. User/group counts
        // are LDAP counts scoped to the profile's targetOuDn — that's what
        // "how many things does this profile actually let me see" means.
        // Counts are memoised per (directoryId, targetOuDn) so profiles
        // sharing a scope only cost one LDAP query each (users + groups).
        Map<String, Long> userScopeCache = new HashMap<>();
        Map<String, Long> groupScopeCache = new HashMap<>();
        List<ProfileStatDto> profileStats = profileRoleRepo.findAllByAdminAccountId(principal.id()).stream()
                .filter(r -> r.getProfile() != null)
                // Hide disabled profiles. Admins act through profiles; a
                // disabled one can't be used to provision, so showing
                // its user/group counts on the dashboard is misleading
                // (the row implies live traffic). The Profile picker
                // and /auth/me/profiles already filter the same way;
                // keep the admin-facing surfaces consistent.
                // Side-benefit: skips the per-profile LDAP scope counts
                // for disabled rows, which is the dashboard's most
                // expensive work.
                .filter(r -> r.getProfile().isEnabled())
                .map(r -> {
                    var p = r.getProfile();
                    var d = p.getDirectory();
                    long pending = approvalRepo.countByProfileIdAndStatus(p.getId(), ApprovalStatus.PENDING);
                    long userCount = d != null ? countUsersInScope(d, p.getTargetOuDn(), userScopeCache) : 0L;
                    long groupCount = d != null ? countGroupsInScope(d, p.getTargetOuDn(), groupScopeCache) : 0L;
                    return new ProfileStatDto(
                            p.getId().toString(),
                            p.getName(),
                            d != null ? d.getId().toString() : null,
                            d != null ? d.getDisplayName() : null,
                            r.getBaseRole() != null ? r.getBaseRole().name() : null,
                            p.getTargetOuDn(),
                            userCount,
                            groupCount,
                            pending);
                })
                .sorted(Comparator
                        .comparing(ProfileStatDto::directoryName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(ProfileStatDto::name, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        // "Users/Groups in scope" — sum of per-profile counts. Surfaced on the
        // admin dashboard as the top-row totalUsers/totalGroups cards so they
        // reflect what's visible under the admin's profile target OUs rather
        // than the raw directory total (which used to overstate the real
        // scope). -1 entries are LDAP failures and are skipped. Profiles with
        // overlapping scopes will double-count — acceptable fuzz for a
        // dashboard aggregate, and the per-row Profiles panel below shows the
        // underlying numbers so discrepancies are visible.
        long totalUsers = profileStats.stream().mapToLong(ProfileStatDto::userCount).filter(n -> n >= 0).sum();
        long totalGroups = profileStats.stream().mapToLong(ProfileStatDto::groupCount).filter(n -> n >= 0).sum();

        // ── Recent activity ──────────────────────────────────────────────────
        var recentAudit = auditQueryService.queryForDirectories(
                authorizedDirIds, null, null, null, null, null, 0, 10);

        String firstDirId = dirs.isEmpty() ? null : dirs.get(0).getId().toString();

        return new AdminDashboardDto(
                totalUsers, totalGroups, totalPending,
                totalSodViolations, totalActiveCampaigns, campaignCompletionPct, overdueCampaigns,
                approvalAging, approvalsConfigured,
                campaignProgress, dirStats, profileStats,
                recentAudit.getContent(),
                firstDirId);
    }

    private ApprovalAgingDto computeApprovalAging(Set<UUID> directoryIds, OffsetDateTime now) {
        long lt24h = 0, d1to3 = 0, d3to7 = 0, gt7d = 0;

        for (UUID dirId : directoryIds) {
            List<PendingApproval> pendingApprovals =
                    approvalRepo.findAllByDirectoryIdAndStatus(dirId, ApprovalStatus.PENDING);

            for (PendingApproval pa : pendingApprovals) {
                Duration age = Duration.between(pa.getCreatedAt(), now);
                long hours = age.toHours();
                if (hours < 24) lt24h++;
                else if (hours < 72) d1to3++;
                else if (hours < 168) d3to7++;
                else gt7d++;
            }
        }
        return new ApprovalAgingDto(lt24h, d1to3, d3to7, gt7d);
    }

    /**
     * Count users under {@code baseDn} (or directory root if null),
     * memoised on the (directoryId, baseDn) pair. Returns -1 on LDAP
     * failure — the UI renders that as an em-dash. Disabled directories
     * return 0 without touching LDAP.
     */
    private long countUsersInScope(DirectoryConnection dc, String baseDn, Map<String, Long> cache) {
        String key = dc.getId() + "|" + (baseDn == null ? "" : baseDn);
        return cache.computeIfAbsent(key, k -> {
            if (!dc.isEnabled()) return 0L;
            try {
                return (long) userService.searchUsers(
                        dc, USER_OBJECTCLASS_FILTER, baseDn, MAX_COUNT, "1.1").size();
            } catch (Exception e) {
                log.warn("Failed to count users for directory {} scope '{}': {}",
                        dc.getDisplayName(), baseDn, e.getMessage());
                return -1L;
            }
        });
    }

    /** @see #countUsersInScope */
    private long countGroupsInScope(DirectoryConnection dc, String baseDn, Map<String, Long> cache) {
        String key = dc.getId() + "|" + (baseDn == null ? "" : baseDn);
        return cache.computeIfAbsent(key, k -> {
            if (!dc.isEnabled()) return 0L;
            try {
                return (long) groupService.searchGroups(
                        dc, GROUP_OBJECTCLASS_FILTER, baseDn, MAX_COUNT, "1.1").size();
            } catch (Exception e) {
                log.warn("Failed to count groups for directory {} scope '{}': {}",
                        dc.getDisplayName(), baseDn, e.getMessage());
                return -1L;
            }
        });
    }

    private AdminDashboardDto emptyDashboard() {
        return new AdminDashboardDto(
                0, 0, 0, 0, 0, null, 0,
                new ApprovalAgingDto(0, 0, 0, 0),
                false,
                List.of(), List.of(), List.of(), List.of(), null);
    }
}
