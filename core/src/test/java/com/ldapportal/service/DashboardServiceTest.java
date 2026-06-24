// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.core.dashboard.ReportJobHealth;
import com.ldapportal.core.dashboard.ReportJobHealthProvider;
import com.ldapportal.core.governance.GovernanceDashboardProvider;
import com.ldapportal.dto.dashboard.ComplianceDashboardDto;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.enums.ApprovalStatus;
import com.ldapportal.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private PendingApprovalRepository approvalRepo;
    @Mock private AuditQueryService auditQueryService;
    @Mock private ProfileApprovalConfigRepository approvalConfigRepo;
    @Mock private GovernanceDashboardProvider governance;
    @Mock private ReportJobHealthProvider reportJobHealthProvider;
    @Mock private ScopeCountService scopeCountService;

    private DashboardService service;

    private DirectoryConnection directory;
    private final UUID directoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DashboardService(
                dirRepo, approvalRepo, auditQueryService,
                approvalConfigRepo, governance, reportJobHealthProvider, scopeCountService);
        service.invalidateCache();

        directory = new DirectoryConnection();
        directory.setId(directoryId);
        directory.setDisplayName("Test Dir");
        directory.setEnabled(true);
    }

    /** A single {@code [id, count]} GROUP BY row as the repository returns it. */
    private static List<Object[]> pendingRows(UUID id, long count) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{id, count});
        return rows;
    }

    private static Map<UUID, ScopeCountService.ScopeCounts> counts(UUID id, long users, long groups) {
        return Map.of(id, new ScopeCountService.ScopeCounts(users, groups));
    }

    @Test
    void getDashboard_returnsComplianceDashboardDto() {
        stubCommon();
        when(approvalRepo.countPendingByDirectory(eq(ApprovalStatus.PENDING), anyCollection()))
                .thenReturn(pendingRows(directoryId, 5L));

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result).isNotNull();
        assertThat(result.totalPendingApprovals()).isEqualTo(5);
        assertThat(result.openSodViolations()).isEqualTo(0);
        assertThat(result.directories()).hasSize(1);
        assertThat(result.directories().get(0).name()).isEqualTo("Test Dir");
    }

    @Test
    void getDashboard_campaignCompletionPercent_calculatedCorrectly() {
        stubCommon();

        var row = new GovernanceDashboardProvider.CampaignProgressRow(
                UUID.randomUUID().toString(), "Q1 Review", "Test Dir", directoryId,
                100L, 80L, 80.0, false,
                OffsetDateTime.now().plusDays(7).toString());
        when(governance.activeCampaignProgress()).thenReturn(List.of(row));
        when(governance.directoryCounts(directoryId)).thenReturn(
                new GovernanceDashboardProvider.DirectoryGovernanceCounts(1L, 0L));

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result.campaignCompletionPercent()).isEqualTo(80.0);
        assertThat(result.campaignProgress()).hasSize(1);
        assertThat(result.campaignProgress().get(0).completionPercent()).isEqualTo(80.0);
        assertThat(result.campaignProgress().get(0).overdue()).isFalse();
    }

    @Test
    void getDashboard_overdueCampaign_flaggedCorrectly() {
        stubCommon();

        var row = new GovernanceDashboardProvider.CampaignProgressRow(
                UUID.randomUUID().toString(), "Overdue Review", "Test Dir", directoryId,
                50L, 20L, 40.0, true,
                OffsetDateTime.now().minusDays(3).toString());
        when(governance.activeCampaignProgress()).thenReturn(List.of(row));
        when(governance.overdueCampaignsCount()).thenReturn(1L);
        when(governance.directoryCounts(directoryId)).thenReturn(
                new GovernanceDashboardProvider.DirectoryGovernanceCounts(1L, 0L));

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result.overdueCampaigns()).isEqualTo(1);
        assertThat(result.campaignProgress().get(0).overdue()).isTrue();
    }

    @Test
    void getDashboard_approvalAgingBuckets_computedCorrectly() {
        stubCommon();

        OffsetDateTime now = OffsetDateTime.now();
        List<PendingApproval> approvals = List.of(
                buildApproval(now.minusHours(2)),
                buildApproval(now.minusDays(2)),
                buildApproval(now.minusDays(5)),
                buildApproval(now.minusDays(10))
        );
        when(approvalRepo.findAllByStatus(ApprovalStatus.PENDING)).thenReturn(approvals);

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result.approvalAging().lessThan24h()).isEqualTo(1);
        assertThat(result.approvalAging().oneToThreeDays()).isEqualTo(1);
        assertThat(result.approvalAging().threeToSevenDays()).isEqualTo(1);
        assertThat(result.approvalAging().moreThanSevenDays()).isEqualTo(1);
    }

    @Test
    void getDashboard_usersNotReviewedIn90Days_reusesPerDirectoryCount() {
        when(dirRepo.findAll()).thenReturn(List.of(directory));
        // 50 users in scope; the 90-day calc must reuse this, not re-count.
        when(scopeCountService.countDirectories(anyList())).thenReturn(counts(directoryId, 50L, 0L));
        when(approvalRepo.countPendingByDirectory(any(), any())).thenReturn(List.of());
        when(approvalRepo.findAllByStatus(any())).thenReturn(List.of());
        when(governance.directoryCounts(any())).thenReturn(
                new GovernanceDashboardProvider.DirectoryGovernanceCounts(0L, 0L));
        when(governance.totalOpenSodViolations()).thenReturn(0L);
        when(governance.activeCampaignProgress()).thenReturn(List.of());
        when(governance.overdueCampaignsCount()).thenReturn(0L);
        when(governance.reviewedUsersSince(eq(directoryId), any())).thenReturn(30L);
        when(auditQueryService.query(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        when(reportJobHealthProvider.health()).thenReturn(ReportJobHealth.empty());

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result.usersNotReviewedIn90Days()).isEqualTo(20);
        // The directory is counted exactly once — the 90-day calc reuses that
        // result instead of issuing a second whole-directory user search.
        verify(scopeCountService, times(1)).countDirectories(anyList());
    }

    @Test
    void getDashboard_noCampaigns_returnsNullCompletion() {
        stubCommon();

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result.campaignCompletionPercent()).isNull();
        assertThat(result.campaignProgress()).isEmpty();
    }

    @Test
    void getDashboard_disabledDirectory_countsZeroAndUnknownReachable() {
        directory.setEnabled(false);
        when(dirRepo.findAll()).thenReturn(List.of(directory));
        // A disabled directory comes back as (0, 0) from the counter without
        // touching LDAP (that shortcut is covered in ScopeCountServiceTest).
        when(scopeCountService.countDirectories(anyList())).thenReturn(counts(directoryId, 0L, 0L));
        when(approvalRepo.countPendingByDirectory(any(), any())).thenReturn(List.of());
        when(approvalRepo.findAllByStatus(any())).thenReturn(List.of());
        when(governance.directoryCounts(any())).thenReturn(
                new GovernanceDashboardProvider.DirectoryGovernanceCounts(0L, 0L));
        when(governance.totalOpenSodViolations()).thenReturn(0L);
        when(governance.activeCampaignProgress()).thenReturn(List.of());
        when(governance.overdueCampaignsCount()).thenReturn(0L);
        when(auditQueryService.query(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        when(reportJobHealthProvider.health()).thenReturn(ReportJobHealth.empty());

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result.directories().get(0).userCount()).isEqualTo(0);
        // Disabled directories aren't probed, so reachability is unknown.
        assertThat(result.directories().get(0).reachable()).isNull();
    }

    @Test
    void getDashboard_enabledReachableDirectory_marksReachableTrue() {
        stubCommon();

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result.directories().get(0).reachable()).isTrue();
    }

    @Test
    void getDashboard_enabledButUnreachableDirectory_marksReachableFalse() {
        // The count comes back as -1 (the counter caught an LDAP failure) — the
        // directory is enabled but its host can't be reached.
        when(dirRepo.findAll()).thenReturn(List.of(directory));
        when(scopeCountService.countDirectories(anyList())).thenReturn(counts(directoryId, -1L, -1L));
        when(approvalRepo.countPendingByDirectory(any(), any())).thenReturn(List.of());
        when(approvalRepo.findAllByStatus(any())).thenReturn(List.of());
        when(governance.directoryCounts(any())).thenReturn(
                new GovernanceDashboardProvider.DirectoryGovernanceCounts(0L, 0L));
        when(governance.totalOpenSodViolations()).thenReturn(0L);
        when(governance.activeCampaignProgress()).thenReturn(List.of());
        when(governance.overdueCampaignsCount()).thenReturn(0L);
        when(auditQueryService.query(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        when(reportJobHealthProvider.health()).thenReturn(ReportJobHealth.empty());

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result.directories().get(0).reachable()).isFalse();
        // The failed count still renders as an em-dash downstream (-1 sentinel).
        assertThat(result.directories().get(0).userCount()).isEqualTo(-1);
    }

    @Test
    void getDashboard_perDirectorySodViolations_included() {
        stubCommon();
        when(governance.directoryCounts(directoryId)).thenReturn(
                new GovernanceDashboardProvider.DirectoryGovernanceCounts(0L, 3L));
        when(governance.totalOpenSodViolations()).thenReturn(3L);

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result.openSodViolations()).isEqualTo(3);
        assertThat(result.directories().get(0).openSodViolations()).isEqualTo(3);
    }

    @Test
    void getDashboard_includesReportJobStats() {
        stubCommon();
        when(reportJobHealthProvider.health()).thenReturn(new ReportJobHealth(5L, 2L));

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result.enabledReportJobs()).isEqualTo(5);
        assertThat(result.failedReportJobs()).isEqualTo(2);
    }

    @Test
    void getDashboard_cacheReturnsCachedResult() {
        stubCommon();

        ComplianceDashboardDto first = service.getDashboard();
        ComplianceDashboardDto second = service.getDashboard();

        assertThat(second).isSameAs(first);
        verify(dirRepo, times(1)).findAll();
    }

    @Test
    void getDashboard_withoutScopeCounts_skipsLdapAndZeroesCounts() {
        when(dirRepo.findAll()).thenReturn(List.of(directory));
        when(approvalRepo.countPendingByDirectory(any(), any())).thenReturn(List.of());
        when(approvalRepo.findAllByStatus(any())).thenReturn(List.of());
        when(governance.directoryCounts(any())).thenReturn(
                new GovernanceDashboardProvider.DirectoryGovernanceCounts(0L, 0L));
        when(governance.totalOpenSodViolations()).thenReturn(0L);
        when(governance.activeCampaignProgress()).thenReturn(List.of());
        when(governance.overdueCampaignsCount()).thenReturn(0L);
        when(auditQueryService.query(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        when(reportJobHealthProvider.health()).thenReturn(ReportJobHealth.empty());

        ComplianceDashboardDto result = service.getDashboard(false);

        // The fast phase issues no LDAP counts at all.
        verify(scopeCountService, never()).countDirectories(any());
        assertThat(result.totalUsers()).isZero();
        assertThat(result.directories().get(0).userCount()).isZero();
        // Not probed in the fast phase, so reachability is unknown.
        assertThat(result.directories().get(0).reachable()).isNull();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubCommon() {
        when(dirRepo.findAll()).thenReturn(List.of(directory));
        when(scopeCountService.countDirectories(anyList())).thenReturn(counts(directoryId, 0L, 0L));
        when(approvalRepo.countPendingByDirectory(any(), any())).thenReturn(List.of());
        when(approvalRepo.findAllByStatus(any())).thenReturn(List.of());
        when(governance.directoryCounts(any())).thenReturn(
                new GovernanceDashboardProvider.DirectoryGovernanceCounts(0L, 0L));
        when(governance.totalOpenSodViolations()).thenReturn(0L);
        when(governance.activeCampaignProgress()).thenReturn(List.of());
        when(governance.overdueCampaignsCount()).thenReturn(0L);
        when(auditQueryService.query(any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        when(reportJobHealthProvider.health()).thenReturn(ReportJobHealth.empty());
    }

    private PendingApproval buildApproval(OffsetDateTime createdAt) {
        PendingApproval pa = new PendingApproval();
        pa.setId(UUID.randomUUID());
        pa.setStatus(ApprovalStatus.PENDING);
        pa.setCreatedAt(createdAt);
        return pa;
    }
}
