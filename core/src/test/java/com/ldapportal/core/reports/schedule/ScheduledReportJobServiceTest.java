// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.reports.ReportData;
import com.ldapportal.dto.reports.ReportJobRequest;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ScheduledReportJob;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ScheduledReportJobRepository;
import com.ldapportal.service.EmailService;
import com.ldapportal.service.S3UploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledReportJobServiceTest {

    private final ScheduledReportJobRepository jobRepo = mock(ScheduledReportJobRepository.class);
    private final DirectoryConnectionRepository dirRepo = mock(DirectoryConnectionRepository.class);
    private final ScheduledReportContentProvider contentProvider = mock(ScheduledReportContentProvider.class);
    private final ReportRenderer renderer = mock(ReportRenderer.class);
    private final EntitlementService entitlements = mock(EntitlementService.class);
    private final EmailService emailService = mock(EmailService.class);
    private final S3UploadService s3 = mock(S3UploadService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-20T08:00:00Z"), ZoneOffset.UTC);
    private final PermissionService permissionService = mock(PermissionService.class);
    private final AccountRepository accountRepo = mock(AccountRepository.class);

    private ScheduledReportJobService service;
    private final UUID dirId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        service = new ScheduledReportJobService(jobRepo, dirRepo, List.of(contentProvider), List.of(renderer),
                entitlements, emailService, s3, clock, permissionService, accountRepo);
        when(contentProvider.supportedTypes())
                .thenReturn(List.of(ScheduledReportType.core("DISABLED_ACCOUNTS", "Disabled Accounts")));
        when(entitlements.exposes(any())).thenReturn(true);
    }

    private ReportJobRequest req(ReportOutputFormat fmt, ReportDeliveryMethod delivery, String recipient) {
        return new ReportJobRequest("Weekly", "DISABLED_ACCOUNTS", Map.of("lookbackDays", 30),
                "0 8 * * MON", fmt, delivery, recipient, null, "America/New_York", true);
    }

    // ── validation ──

    @Test
    void create_normalizesCron_setsCreatedBy_andSaves() {
        when(jobRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        AuthPrincipal principal = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "admin");
        when(permissionService.resolveSearchBaseDns(principal, dirId, null)).thenReturn(List.of("ou=people,dc=example,dc=com"));

        ScheduledReportJob saved = service.create(dirId, req(ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL, "a@b.com"), principal);

        assertThat(saved.getCronExpression()).isEqualTo("0 0 8 * * MON"); // 5→6 field
        assertThat(saved.getCreatedByAdminId()).isEqualTo(principal.id());
        assertThat(saved.getDeliveryRecipients()).isEqualTo("a@b.com");
        assertThat(saved.getTimezone()).isEqualTo("America/New_York");
    }

    @Test
    void create_rejectsUnknownType() {
        ReportJobRequest r = new ReportJobRequest("x", "NOPE", null, "0 8 * * MON",
                ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL, "a@b.com", null, null, true);
        assertThatThrownBy(() -> service.create(dirId, r, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown report type");
    }

    @Test
    void create_rejectsGatedFormat_whenNotExposed() {
        when(entitlements.exposes(ReportOutputFormat.PDF)).thenReturn(false);
        assertThatThrownBy(() -> service.create(dirId, req(ReportOutputFormat.PDF, ReportDeliveryMethod.EMAIL, "a@b.com"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PDF output requires");
    }

    @Test
    void create_rejectsGatedDelivery_whenNotExposed() {
        when(entitlements.exposes(ReportDeliveryMethod.S3)).thenReturn(false);
        assertThatThrownBy(() -> service.create(dirId, req(ReportOutputFormat.CSV, ReportDeliveryMethod.S3, null), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("S3 delivery requires");
    }

    @Test
    void create_rejectsEmailWithoutRecipient() {
        assertThatThrownBy(() -> service.create(dirId, req(ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL, "  "), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recipient");
    }

    @Test
    void create_rejectsBadCronAndBadTimezone() {
        ReportJobRequest badCron = new ReportJobRequest("x", "DISABLED_ACCOUNTS", null, "not a cron",
                ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL, "a@b.com", null, null, true);
        assertThatThrownBy(() -> service.create(dirId, badCron, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cron");

        ReportJobRequest badTz = new ReportJobRequest("x", "DISABLED_ACCOUNTS", null, "0 8 * * MON",
                ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL, "a@b.com", null, "Mars/Phobos", true);
        assertThatThrownBy(() -> service.create(dirId, badTz, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timezone");
    }

    // ── runJob ──

    private ScheduledReportJob job(ReportOutputFormat fmt, ReportDeliveryMethod delivery) {
        ScheduledReportJob j = new ScheduledReportJob();
        j.setId(UUID.randomUUID());
        j.setDirectoryId(dirId);
        j.setName("Weekly");
        j.setReportType("DISABLED_ACCOUNTS");
        j.setOutputFormat(fmt);
        j.setDeliveryMethod(delivery);
        j.setDeliveryRecipients("a@b.com");
        j.setRunHistory(new ArrayList<>());
        return j;
    }

    @Test
    void runJob_success_emailDelivery_recordsSuccessAndHistory() {
        ScheduledReportJob j = job(ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL);
        DirectoryConnection dc = mock(DirectoryConnection.class);
        when(dc.getDisplayName()).thenReturn("Corp LDAP");
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(contentProvider.appliesTo(dc, "DISABLED_ACCOUNTS")).thenReturn(true);
        when(contentProvider.run(eq(dc), eq("DISABLED_ACCOUNTS"), any(), any(), any()))
                .thenReturn(new ReportData(List.of("c"), List.of()));
        when(renderer.supports(ReportOutputFormat.CSV)).thenReturn(true);
        when(renderer.render(any(), any())).thenReturn(new RenderedReport(new byte[]{1, 2, 3}, "text/csv", "r.csv"));
        when(emailService.sendWithAttachment(any(), any(), any(), any(), any(), any()))
                .thenReturn(EmailService.SendResult.SENT);
        when(jobRepo.findById(j.getId())).thenReturn(Optional.of(j));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        verify(emailService).sendWithAttachment(eq("a@b.com"), anyString(), anyString(),
                eq("r.csv"), eq("text/csv"), any());
        assertThat(j.getLastRunStatus()).isEqualTo(ReportJobRunStatus.SUCCESS);
        assertThat(j.getRunHistory()).singleElement().satisfies(h -> {
            assertThat(h.status()).isEqualTo(ReportJobRunStatus.SUCCESS);
            assertThat(h.trigger()).isEqualTo(ReportRunTrigger.SCHEDULED);
            assertThat(h.startedAt()).isNotNull();
            assertThat(h.runAt()).isNotNull();
        });
    }

    @Test
    void runJob_emailSkipped_whenSmtpNotConfigured_recordsSkipped() {
        ScheduledReportJob j = job(ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL);
        DirectoryConnection dc = mock(DirectoryConnection.class);
        when(dc.getDisplayName()).thenReturn("Corp LDAP");
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(contentProvider.appliesTo(dc, "DISABLED_ACCOUNTS")).thenReturn(true);
        when(contentProvider.run(any(), any(), any(), any(), any())).thenReturn(new ReportData(List.of("c"), List.of()));
        when(renderer.supports(ReportOutputFormat.CSV)).thenReturn(true);
        when(renderer.render(any(), any())).thenReturn(new RenderedReport(new byte[]{1, 2, 3}, "text/csv", "r.csv"));
        when(emailService.sendWithAttachment(any(), any(), any(), any(), any(), any()))
                .thenReturn(EmailService.SendResult.SKIPPED);
        when(jobRepo.findById(j.getId())).thenReturn(Optional.of(j));

        service.runJob(j, ReportRunTrigger.MANUAL);

        // Report generated, but nothing delivered → SKIPPED, never SUCCESS.
        assertThat(j.getLastRunStatus()).isEqualTo(ReportJobRunStatus.SKIPPED);
        assertThat(j.getLastRunMessage()).contains("SMTP is not configured");
    }

    @Test
    void runJob_emailDeliveryRejected_recordsFailed() {
        ScheduledReportJob j = job(ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL);
        DirectoryConnection dc = mock(DirectoryConnection.class);
        when(dc.getDisplayName()).thenReturn("Corp LDAP");
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(contentProvider.appliesTo(dc, "DISABLED_ACCOUNTS")).thenReturn(true);
        when(contentProvider.run(any(), any(), any(), any(), any())).thenReturn(new ReportData(List.of("c"), List.of()));
        when(renderer.supports(ReportOutputFormat.CSV)).thenReturn(true);
        when(renderer.render(any(), any())).thenReturn(new RenderedReport(new byte[]{1, 2, 3}, "text/csv", "r.csv"));
        when(emailService.sendWithAttachment(any(), any(), any(), any(), any(), any()))
                .thenReturn(EmailService.SendResult.FAILED);
        when(jobRepo.findById(j.getId())).thenReturn(Optional.of(j));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        assertThat(j.getLastRunStatus()).isEqualTo(ReportJobRunStatus.FAILED);
        assertThat(j.getLastRunMessage()).contains("delivery failed for").contains("a@b.com");
    }

    @Test
    void runJob_s3Delivery_uploadsObject() throws Exception {
        ScheduledReportJob j = job(ReportOutputFormat.CSV, ReportDeliveryMethod.S3);
        j.setS3KeyPrefix("reports/");
        DirectoryConnection dc = mock(DirectoryConnection.class);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(contentProvider.appliesTo(dc, "DISABLED_ACCOUNTS")).thenReturn(true);
        when(contentProvider.run(any(), any(), any(), any(), any())).thenReturn(new ReportData(List.of("c"), List.of()));
        when(renderer.supports(ReportOutputFormat.CSV)).thenReturn(true);
        when(renderer.render(any(), any())).thenReturn(new RenderedReport(new byte[]{9}, "text/csv", "r.csv"));
        when(jobRepo.findById(j.getId())).thenReturn(Optional.of(j));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        verify(s3).upload(eq("reports/r.csv"), any(), eq("text/csv"));
        assertThat(j.getLastRunStatus()).isEqualTo(ReportJobRunStatus.SUCCESS);
    }

    @Test
    void runJob_gatedAfterDowngrade_recordsFailed_doesNotRun() {
        ScheduledReportJob j = job(ReportOutputFormat.PDF, ReportDeliveryMethod.EMAIL);
        when(jobRepo.findById(j.getId())).thenReturn(Optional.of(j));
        when(entitlements.exposes(ReportOutputFormat.PDF)).thenReturn(false);

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        assertThat(j.getLastRunStatus()).isEqualTo(ReportJobRunStatus.FAILED);
        assertThat(j.getLastRunMessage()).contains("PDF output requires");
        verify(emailService, never()).sendWithAttachment(any(), any(), any(), any(), any(), any());
    }

    @Test
    void runJob_emailWithNoUsableRecipient_recordsSkipped_doesNotSend() {
        ScheduledReportJob j = job(ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL);
        j.setDeliveryRecipients(" , "); // blank/comma-only — splits to zero addresses
        DirectoryConnection dc = mock(DirectoryConnection.class);
        when(dc.getDisplayName()).thenReturn("Corp LDAP");
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(contentProvider.appliesTo(dc, "DISABLED_ACCOUNTS")).thenReturn(true);
        when(contentProvider.run(any(), any(), any(), any(), any())).thenReturn(new ReportData(List.of("c"), List.of()));
        when(renderer.supports(ReportOutputFormat.CSV)).thenReturn(true);
        when(renderer.render(any(), any())).thenReturn(new RenderedReport(new byte[]{1, 2, 3}, "text/csv", "r.csv"));
        when(jobRepo.findById(j.getId())).thenReturn(Optional.of(j));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        // Generated but undeliverable → SKIPPED, never SUCCESS, and no send attempted.
        assertThat(j.getLastRunStatus()).isEqualTo(ReportJobRunStatus.SKIPPED);
        assertThat(j.getLastRunMessage()).contains("no recipient address");
        verify(emailService, never()).sendWithAttachment(any(), any(), any(), any(), any(), any());
    }

    @Test
    void runJob_unknownType_recordsFailed() {
        ScheduledReportJob j = job(ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL);
        j.setReportType("GONE");
        when(jobRepo.findById(j.getId())).thenReturn(Optional.of(j));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        assertThat(j.getLastRunStatus()).isEqualTo(ReportJobRunStatus.FAILED);
        assertThat(j.getLastRunMessage()).contains("Unknown report type");
    }

    @Test
    void runJob_providerThrows_recordsFailed_neverThrows() {
        ScheduledReportJob j = job(ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL);
        DirectoryConnection dc = mock(DirectoryConnection.class);
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(contentProvider.appliesTo(dc, "DISABLED_ACCOUNTS")).thenReturn(true);
        when(contentProvider.run(any(), any(), any(), any(), any())).thenThrow(new RuntimeException("ldap down"));
        when(jobRepo.findById(j.getId())).thenReturn(Optional.of(j));

        service.runJob(j, ReportRunTrigger.SCHEDULED); // must not throw

        assertThat(j.getLastRunStatus()).isEqualTo(ReportJobRunStatus.FAILED);
        assertThat(j.getLastRunMessage()).contains("ldap down");
    }

    @Test
    void recordResult_trimsHistoryToBound() {
        ScheduledReportJob j = job(ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL);
        when(jobRepo.findById(j.getId())).thenReturn(Optional.of(j));
        for (int i = 0; i < ScheduledReportJobService.MAX_RUN_HISTORY + 3; i++) {
            service.recordResult(j.getId(), OffsetDateTime.now(clock), ReportJobRunStatus.SUCCESS, "run " + i,
                    ReportRunTrigger.SCHEDULED);
        }
        assertThat(j.getRunHistory()).hasSize(ScheduledReportJobService.MAX_RUN_HISTORY);
        assertThat(j.getRunHistory().get(j.getRunHistory().size() - 1).message()).isEqualTo("run 12");
    }

    @Test
    void normalizeCron_prependsSecondsForFiveField() {
        assertThat(ScheduledReportJobService.normalizeCron("0 8 * * MON")).isEqualTo("0 0 8 * * MON");
        assertThat(ScheduledReportJobService.normalizeCron("0 0 8 * * MON")).isEqualTo("0 0 8 * * MON");
    }

    // ── creator scope: the scheduler runs as the system, so pin it at save ──

    private ReportJobRequest reqWithParams(Map<String, Object> params) {
        return new ReportJobRequest("Weekly", "DISABLED_ACCOUNTS", params,
                "0 8 * * MON", ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL, "a@b.com", null, "UTC", true);
    }

    @Test
    void create_admin_withoutScope_storesTheirSingleAuthorizedOu() {
        when(jobRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "admin");
        when(permissionService.resolveSearchBaseDns(admin, dirId, null)).thenReturn(List.of("ou=people,dc=example,dc=com"));

        ScheduledReportJob saved = service.create(dirId, reqWithParams(Map.of("lookbackDays", 30)), admin);

        assertThat(saved.getReportParams()).containsEntry("scopeBaseDn", "ou=people,dc=example,dc=com");
    }

    @Test
    void create_admin_requestedScopeIsClamped_notTrusted() {
        when(jobRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "admin");
        when(permissionService.resolveSearchBaseDns(admin, dirId, "dc=example,dc=com"))
                .thenReturn(List.of("ou=people,dc=example,dc=com"));

        ScheduledReportJob saved = service.create(dirId,
                reqWithParams(Map.of("scopeBaseDn", "dc=example,dc=com")), admin);

        assertThat(saved.getReportParams()).containsEntry("scopeBaseDn", "ou=people,dc=example,dc=com");
    }

    @Test
    void create_admin_scopeOutsideTheirOus_isRefusedBeforeSaving() {
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "admin");
        when(permissionService.resolveSearchBaseDns(admin, dirId, "ou=other,dc=example,dc=com"))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("outside"));

        assertThatThrownBy(() -> service.create(dirId,
                reqWithParams(Map.of("scopeBaseDn", "ou=other,dc=example,dc=com")), admin))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(jobRepo, never()).save(any());
    }

    @Test
    void create_admin_severalOusAndNoScope_mustPickOne() {
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "admin");
        when(permissionService.resolveSearchBaseDns(admin, dirId, null))
                .thenReturn(List.of("ou=a,dc=example,dc=com", "ou=b,dc=example,dc=com"));

        assertThatThrownBy(() -> service.create(dirId, reqWithParams(Map.of()), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scopeBaseDn");
        verify(jobRepo, never()).save(any());
    }

    @Test
    void create_admin_branchDnParam_isCheckedAgainstTheirScope() {
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "admin");
        when(permissionService.resolveSearchBaseDns(admin, dirId, "ou=people,dc=example,dc=com"))
                .thenReturn(List.of("ou=people,dc=example,dc=com"));
        org.mockito.Mockito.doThrow(new org.springframework.security.access.AccessDeniedException("outside"))
                .when(permissionService).requireDnWithinScope(admin, dirId, "ou=other,dc=example,dc=com");

        assertThatThrownBy(() -> service.create(dirId,
                reqWithParams(Map.of("scopeBaseDn", "ou=people,dc=example,dc=com",
                        "branchDn", "ou=other,dc=example,dc=com")), admin))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verify(jobRepo, never()).save(any());
    }

    @Test
    void create_admin_severalOus_noScope_branchDnSettlesTheScope() {
        when(jobRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "admin");
        String branch = "ou=staff,ou=b,dc=example,dc=com";
        when(permissionService.resolveSearchBaseDns(admin, dirId, branch)).thenReturn(List.of(branch));

        ScheduledReportJob saved = service.create(dirId, reqWithParams(Map.of("branchDn", branch)), admin);

        assertThat(saved.getReportParams())
                .containsEntry("scopeBaseDn", branch)
                .containsEntry("branchDn", branch);
        verify(permissionService).requireDnWithinScope(admin, dirId, branch);
    }

    @Test
    void create_admin_branchDnOutsideTheirOus_isRefusedBeforeSaving() {
        AuthPrincipal admin = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "admin");
        when(permissionService.resolveSearchBaseDns(admin, dirId, "ou=other,dc=example,dc=com"))
                .thenThrow(new org.springframework.security.access.AccessDeniedException("outside"));

        assertThatThrownBy(() -> service.create(dirId,
                reqWithParams(Map.of("branchDn", "ou=other,dc=example,dc=com")), admin))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void create_superadmin_scopeIsStoredAsSent() {
        when(jobRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        AuthPrincipal root = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "root");

        ScheduledReportJob saved = service.create(dirId, reqWithParams(Map.of("lookbackDays", 30)), root);

        assertThat(saved.getReportParams()).doesNotContainKey("scopeBaseDn");
        org.mockito.Mockito.verifyNoInteractions(permissionService);
    }

    // ── run-as: a scheduled run gets what its creator would get on demand ──

    /** A job wired for a successful CSV-by-email run, with the provider's output captured. */
    private ScheduledReportJob runnableJob(ReportData output) {
        ScheduledReportJob j = job(ReportOutputFormat.CSV, ReportDeliveryMethod.EMAIL);
        DirectoryConnection dc = mock(DirectoryConnection.class);
        when(dc.getDisplayName()).thenReturn("Corp LDAP");
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
        when(contentProvider.appliesTo(dc, "DISABLED_ACCOUNTS")).thenReturn(true);
        when(contentProvider.run(any(), any(), any(), any(), any())).thenReturn(output);
        when(renderer.supports(ReportOutputFormat.CSV)).thenReturn(true);
        when(renderer.render(any(), any())).thenReturn(new RenderedReport(new byte[]{1}, "text/csv", "r.csv"));
        when(emailService.sendWithAttachment(any(), any(), any(), any(), any(), any()))
                .thenReturn(EmailService.SendResult.SENT);
        when(jobRepo.findById(j.getId())).thenReturn(Optional.of(j));
        return j;
    }

    private Account account(UUID id, AccountRole role, boolean active) {
        Account a = new Account();
        a.setId(id);
        a.setUsername("alice");
        a.setRole(role);
        a.setActive(active);
        return a;
    }

    private AuthPrincipal capturedRunAs() {
        ArgumentCaptor<AuthPrincipal> runAs = ArgumentCaptor.forClass(AuthPrincipal.class);
        verify(contentProvider).run(any(), eq("DISABLED_ACCOUNTS"), any(), any(), runAs.capture());
        return runAs.getValue();
    }

    @Test
    void runJob_adminsJob_runsAsThatAdmin() {
        ScheduledReportJob j = runnableJob(new ReportData(List.of("dn"), List.of()));
        UUID adminId = UUID.randomUUID();
        j.setCreatedByAdminId(adminId);
        when(accountRepo.findById(adminId)).thenReturn(Optional.of(account(adminId, AccountRole.ADMIN, true)));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        assertThat(capturedRunAs()).isEqualTo(new AuthPrincipal(PrincipalType.ADMIN, adminId, "alice"));
        assertThat(j.getLastRunStatus()).isEqualTo(ReportJobRunStatus.SUCCESS);
    }

    @Test
    void runJob_superadminsJob_runsUnbounded() {
        ScheduledReportJob j = runnableJob(new ReportData(List.of("dn"), List.of()));
        UUID rootId = UUID.randomUUID();
        j.setCreatedByAdminId(rootId);
        when(accountRepo.findById(rootId)).thenReturn(Optional.of(account(rootId, AccountRole.SUPERADMIN, true)));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        assertThat(capturedRunAs()).isNull();
    }

    @Test
    void runJob_jobWithNoRecordedCreator_runsUnbounded() {
        ScheduledReportJob j = runnableJob(new ReportData(List.of("dn"), List.of()));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        assertThat(capturedRunAs()).isNull();
        verify(accountRepo, never()).findById(any());
    }

    @Test
    void runJob_deactivatedCreator_recordsFailed_doesNotRun() {
        ScheduledReportJob j = runnableJob(new ReportData(List.of("dn"), List.of()));
        UUID adminId = UUID.randomUUID();
        j.setCreatedByAdminId(adminId);
        when(accountRepo.findById(adminId)).thenReturn(Optional.of(account(adminId, AccountRole.ADMIN, false)));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        verify(contentProvider, never()).run(any(), any(), any(), any(), any());
        assertThat(j.getLastRunStatus()).isEqualTo(ReportJobRunStatus.FAILED);
        assertThat(j.getLastRunMessage()).contains("deactivated");
    }

    @Test
    void runJob_creatorLostTheJobsOu_recordsFailed_sendsNothing() {
        ScheduledReportJob j = runnableJob(new ReportData(List.of("dn"), List.of()));
        UUID adminId = UUID.randomUUID();
        j.setCreatedByAdminId(adminId);
        when(accountRepo.findById(adminId)).thenReturn(Optional.of(account(adminId, AccountRole.ADMIN, true)));
        when(contentProvider.run(any(), any(), any(), any(), any()))
                .thenThrow(new AccessDeniedException("DN is outside authorized OUs for this admin"));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        verify(emailService, never()).sendWithAttachment(any(), any(), any(), any(), any(), any());
        assertThat(j.getLastRunStatus()).isEqualTo(ReportJobRunStatus.FAILED);
        assertThat(j.getLastRunMessage()).contains("outside authorized OUs");
    }

    // ── columns: a scheduled file carries the columns saved with the job ──

    @Test
    void runJob_projectsTheReportToTheStoredColumns_inOrder() {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("dn", "uid=a,ou=people,dc=example,dc=com");
        row.put("cn", "A");
        row.put("mail", "a@example.com");
        row.put("groups", "devs");
        ScheduledReportJob j = runnableJob(new ReportData(List.of("dn", "cn", "mail", "groups"), List.of(row)));
        j.setReportParams(Map.of("columns", List.of("mail", "cn")));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        ArgumentCaptor<ReportData> rendered = ArgumentCaptor.forClass(ReportData.class);
        verify(renderer).render(rendered.capture(), any());
        assertThat(rendered.getValue().columns()).containsExactly("dn", "mail", "cn");
    }

    @Test
    void runJob_withoutStoredColumns_rendersEveryColumn() {
        ScheduledReportJob j = runnableJob(new ReportData(List.of("dn", "cn", "groups"), List.of()));
        j.setReportParams(Map.of("lookbackDays", 30));

        service.runJob(j, ReportRunTrigger.SCHEDULED);

        ArgumentCaptor<ReportData> rendered = ArgumentCaptor.forClass(ReportData.class);
        verify(renderer).render(rendered.capture(), any());
        assertThat(rendered.getValue().columns()).containsExactly("dn", "cn", "groups");
    }

    @Test
    void exportColumns_acceptsAListOrACommaSeparatedString() {
        assertThat(ScheduledReportJobService.exportColumns(Map.of("columns", List.of("dn", "mail"))))
                .containsExactly("dn", "mail");
        assertThat(ScheduledReportJobService.exportColumns(Map.of("columns", "dn, mail ,")))
                .containsExactly("dn", "mail");
        assertThat(ScheduledReportJobService.exportColumns(Map.of())).isNull();
    }
}
