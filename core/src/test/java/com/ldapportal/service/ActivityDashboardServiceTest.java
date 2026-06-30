// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.core.alerting.AlertSummary;
import com.ldapportal.core.alerting.AlertSummaryProvider;
import com.ldapportal.core.alerting.AlertingDashboardProvider;
import com.ldapportal.core.dashboard.ReportJobHealth;
import com.ldapportal.core.dashboard.ReportJobHealthProvider;
import com.ldapportal.core.governance.GovernanceDashboardProvider;
import com.ldapportal.core.hr.HrDashboardProvider;
import com.ldapportal.dto.dashboard.ActivityDashboardResponse;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SyncChangelogHealth;
import com.ldapportal.entra.repository.EntraSyncStateRepository;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.DismissedSuggestionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.MembershipStateCount;
import com.ldapportal.repository.PendingApprovalRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Directory-sync rollups on the activity dashboard. A superadmin gets the
 * lag/stall awareness, dead-letter + review actions, and the dead-letter metric,
 * derived from the membership index and changelog health. An admin gets none —
 * and the sync queries are skipped entirely, since the items deep-link into a
 * superadmin-only surface (the consumer gates again on role + entitlement).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActivityDashboardServiceTest {

    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private PendingApprovalRepository approvalRepo;
    @Mock private ProvisioningProfileRepository profileRepo;
    @Mock private EntraSyncStateRepository syncStateRepo;
    @Mock private DismissedSuggestionRepository dismissedRepo;
    @Mock private PermissionService permissionService;
    @Mock private AuditQueryService auditQueryService;
    @Mock private ApplicationSettingsService settingsService;
    @Mock private AccountRepository accountRepo;
    @Mock private SyncLinkRepository syncLinkRepo;
    @Mock private MembershipRepository membershipRepo;
    @Mock private SyncSetRepository syncSetRepo;
    @Mock private GovernanceDashboardProvider governance;
    @Mock private AlertSummaryProvider alertSummaryProvider;
    @Mock private AlertingDashboardProvider alertingDashboard;
    @Mock private HrDashboardProvider hrDashboard;
    @Mock private ReportJobHealthProvider reportJobHealth;

    private ActivityDashboardService service;

    private final AuthPrincipal superadmin =
            new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "root");
    private final AuthPrincipal admin =
            new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "alice");

    @BeforeEach
    void setUp() {
        service = new ActivityDashboardService(dirRepo, approvalRepo, profileRepo, syncStateRepo,
                dismissedRepo, permissionService, auditQueryService, settingsService, accountRepo,
                syncLinkRepo, membershipRepo, syncSetRepo,
                governance, alertSummaryProvider, alertingDashboard, hrDashboard, reportJobHealth);

        // Neutral defaults: no directories and no governance/alert/report content, so
        // the only items in play are the directory-sync rollups each test sets up.
        when(dirRepo.findAll()).thenReturn(List.of());
        when(permissionService.getAuthorizedDirectoryIds(any())).thenReturn(Set.of());
        when(alertSummaryProvider.summary()).thenReturn(new AlertSummary(0, 0, 0, 0, 0, 0));
        when(governance.overdueCampaignsForDirectories(any())).thenReturn(List.of());
        when(governance.pendingDecisionPromptsForDirectories(any())).thenReturn(List.of());
        when(governance.upcomingDeadlinesForDirectories(any(), anyLong())).thenReturn(List.of());
        when(governance.activeCampaignProgress()).thenReturn(List.of());
        when(dismissedRepo.findAllByAccountId(any())).thenReturn(List.of());
        when(accountRepo.countByRoleAndActiveTrue(any())).thenReturn(1L);
        when(reportJobHealth.health()).thenReturn(new ReportJobHealth(1L, 0L));
    }

    @Test
    void superadmin_getsSyncLagDriftActionsAndMetric() {
        // Build the projection mocks first — each stubs its own getters, which can't
        // happen inside the outer when(...).thenReturn(...) (nested stubbing).
        List<MembershipStateCount> counts = List.of(
                stateCount(MembershipState.APPLIED, 40),
                stateCount(MembershipState.FAILED, 3),
                stateCount(MembershipState.REVIEW, 2));
        when(membershipRepo.countGroupedByState()).thenReturn(counts);
        // One lagging + one stalled link (degraded), five healthy.
        when(syncLinkRepo.countChangelogLinksByHealth()).thenReturn(List.of(
                new Object[]{SyncChangelogHealth.HEALTHY, 5L},
                new Object[]{SyncChangelogHealth.LAGGING, 1L},
                new Object[]{SyncChangelogHealth.STALLED, 1L}));
        when(syncLinkRepo.maxChangelogLag()).thenReturn(1500L);
        // The most-behind link is first — the lag awareness deep-links to it.
        UUID worstLagLinkId = UUID.randomUUID();
        when(syncLinkRepo.findDegradedChangelogLinkIdsByLagDesc())
                .thenReturn(List.of(worstLagLinkId, UUID.randomUUID()));
        // One drifted set (3 differences), one clean (verified, zero), one never verified.
        UUID worstDriftSetId = UUID.randomUUID();
        when(syncSetRepo.findAllByEnabledTrue()).thenReturn(List.of(
                verifiedSet(worstDriftSetId, 2, 0, 1), verifiedSet(UUID.randomUUID(), 0, 0, 0), neverVerifiedSet()));

        ActivityDashboardResponse out = service.build(superadmin);

        // Dead-letter + review actions carry the FAILED / REVIEW counts.
        assertThat(out.actions()).extracting(ActivityDashboardResponse.ActionItem::type)
                .contains("REPLICATION_DEAD_LETTERED", "SYNC_REVIEW_PENDING");
        assertThat(actionOf(out, "REPLICATION_DEAD_LETTERED").count()).isEqualTo(3);
        assertThat(actionOf(out, "SYNC_REVIEW_PENDING").count()).isEqualTo(2);

        // Lag (degraded changelog links) + drift (cached verify) awareness, each
        // deep-linking to the worst offender so the operator lands on the right row.
        assertThat(out.awareness()).extracting(ActivityDashboardResponse.AwarenessItem::type)
                .contains("REPLICATION_LAG_HIGH", "RECONCILIATION_DRIFT_OPEN");
        assertThat(awarenessOf(out, "REPLICATION_LAG_HIGH").link())
                .isEqualTo("/superadmin/directory-sync?link=" + worstLagLinkId);
        assertThat(awarenessOf(out, "RECONCILIATION_DRIFT_OPEN").link())
                .isEqualTo("/superadmin/directory-sync?set=" + worstDriftSetId);

        // The dead-letter metric mirrors the FAILED count.
        assertThat(out.metrics().replicationEventsDeadLettered()).isEqualTo(3);
    }

    @Test
    void admin_getsNoSyncItems_andSyncReposAreNotQueried() {
        ActivityDashboardResponse out = service.build(admin);

        assertThat(out.actions()).extracting(ActivityDashboardResponse.ActionItem::type)
                .doesNotContain("REPLICATION_DEAD_LETTERED", "SYNC_REVIEW_PENDING");
        assertThat(out.awareness()).extracting(ActivityDashboardResponse.AwarenessItem::type)
                .doesNotContain("REPLICATION_LAG_HIGH", "RECONCILIATION_DRIFT_OPEN");
        assertThat(out.metrics().replicationEventsDeadLettered()).isZero();

        // Sync rollups are superadmin-only — the queries must be skipped for an admin.
        verifyNoInteractions(membershipRepo, syncLinkRepo, syncSetRepo);
    }

    private static ActivityDashboardResponse.ActionItem actionOf(ActivityDashboardResponse out, String type) {
        return out.actions().stream().filter(a -> a.type().equals(type)).findFirst().orElseThrow();
    }

    private static ActivityDashboardResponse.AwarenessItem awarenessOf(ActivityDashboardResponse out, String type) {
        return out.awareness().stream().filter(a -> a.type().equals(type)).findFirst().orElseThrow();
    }

    private static MembershipStateCount stateCount(MembershipState state, long cnt) {
        MembershipStateCount c = mock(MembershipStateCount.class);
        when(c.getState()).thenReturn(state);
        when(c.getCnt()).thenReturn(cnt);
        return c;
    }

    private static SyncSet verifiedSet(UUID id, int missing, int orphan, int mismatch) {
        SyncSet s = new SyncSet();
        s.setId(id);
        s.setName("set");
        s.setLastVerifiedAt(OffsetDateTime.now());
        s.setVerifyMissingCount(missing);
        s.setVerifyOrphanCount(orphan);
        s.setVerifyMismatchCount(mismatch);
        return s;
    }

    private static SyncSet neverVerifiedSet() {
        SyncSet s = new SyncSet();
        s.setName("fresh"); // lastVerifiedAt null => no drift claimed
        return s;
    }
}
