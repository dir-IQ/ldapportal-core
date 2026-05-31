// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.core.dashboard.ReportJobHealth;
import com.ldapportal.core.dashboard.ReportJobHealthProvider;
import com.ldapportal.core.governance.GovernanceDashboardProvider;
import com.ldapportal.dto.dashboard.ComplianceDashboardDto;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.enums.ApprovalStatus;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
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
    @Mock private LdapUserService userService;
    @Mock private LdapGroupService groupService;
    @Mock private ProfileApprovalConfigRepository approvalConfigRepo;
    @Mock private GovernanceDashboardProvider governance;
    @Mock private ReportJobHealthProvider reportJobHealthProvider;

    private DashboardService service;

    private DirectoryConnection directory;
    private final UUID directoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DashboardService(
                dirRepo, approvalRepo, auditQueryService,
                userService, groupService, approvalConfigRepo,
                governance, reportJobHealthProvider);
        service.invalidateCache();

        directory = new DirectoryConnection();
        directory.setId(directoryId);
        directory.setDisplayName("Test Dir");
        directory.setEnabled(true);
    }

    @Test
    void getDashboard_returnsComplianceDashboardDto() {
        stubCommon();
        when(approvalRepo.countByDirectoryIdAndStatus(directoryId, ApprovalStatus.PENDING)).thenReturn(5L);
        when(approvalRepo.findAllByStatus(ApprovalStatus.PENDING)).thenReturn(List.of());

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
        when(approvalRepo.countByDirectoryIdAndStatus(any(), any())).thenReturn(4L);

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
    void getDashboard_usersNotReviewedIn90Days_calculatedPerDirectory() {
        var users = new ArrayList<com.ldapportal.ldap.model.LdapUser>();
        for (int i = 0; i < 50; i++) users.add(mock(com.ldapportal.ldap.model.LdapUser.class));
        when(dirRepo.findAll()).thenReturn(List.of(directory));
        when(userService.searchUsers(eq(directory), anyString(), any(), anyInt(), anyString()))
                .thenReturn(users);
        when(groupService.searchGroups(eq(directory), anyString(), any(), anyInt(), anyString()))
                .thenReturn(List.of());
        when(approvalRepo.countByDirectoryIdAndStatus(any(), any())).thenReturn(0L);
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
    }

    @Test
    void getDashboard_noCampaigns_returnsNullCompletion() {
        stubCommon();

        ComplianceDashboardDto result = service.getDashboard();

        assertThat(result.campaignCompletionPercent()).isNull();
        assertThat(result.campaignProgress()).isEmpty();
    }

    @Test
    void getDashboard_disabledDirectory_skipLdapCalls() {
        directory.setEnabled(false);
        when(dirRepo.findAll()).thenReturn(List.of(directory));
        when(approvalRepo.countByDirectoryIdAndStatus(any(), any())).thenReturn(0L);
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

        verify(userService, never()).searchUsers(any(), anyString(), any(), anyInt(), anyString());
        assertThat(result.directories().get(0).userCount()).isEqualTo(0);
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubCommon() {
        when(dirRepo.findAll()).thenReturn(List.of(directory));
        when(userService.searchUsers(eq(directory), anyString(), any(), anyInt(), anyString()))
                .thenReturn(List.of());
        when(groupService.searchGroups(eq(directory), anyString(), any(), anyInt(), anyString()))
                .thenReturn(List.of());
        when(approvalRepo.countByDirectoryIdAndStatus(any(), any())).thenReturn(0L);
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
