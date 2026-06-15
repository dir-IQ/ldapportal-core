// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.core.observability.CorrelationContext;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.AuditSource;
import com.ldapportal.repository.AuditEventRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.core.siem.service.SiemExportService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock private ApplicationEventPublisher      eventPublisher;
    @Mock private AuditEventRepository           auditRepo;
    @Mock private DirectoryConnectionRepository  dirRepo;
    @Mock private SiemExportService              siemExportService;

    private AuditService auditService;

    private final UUID adminId     = UUID.randomUUID();
    private final UUID directoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditRepo, dirRepo, siemExportService, eventPublisher,
                java.util.List.of());
    }

    // ── Internal event recording ──────────────────────────────────────────────

    @Test
    void record_savesInternalEvent_forAdminPrincipal() {
        AuthPrincipal principal = new AuthPrincipal(PrincipalType.ADMIN, adminId, "alice");

        DirectoryConnection dc = mockDirectory("corp-ldap");
        when(dirRepo.findById(directoryId)).thenReturn(Optional.of(dc));

        auditService.record(principal, directoryId, AuditAction.USER_CREATE,
                "uid=alice,ou=users,dc=corp,dc=com",
                Map.of("attributes", "cn,sn,mail"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRepo).save(captor.capture());

        AuditEvent saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(AuditSource.INTERNAL);
        assertThat(saved.getActorId()).isEqualTo(adminId);
        assertThat(saved.getActorType()).isEqualTo("ADMIN");
        assertThat(saved.getActorUsername()).isEqualTo("alice");
        assertThat(saved.getDirectoryId()).isEqualTo(directoryId);
        assertThat(saved.getDirectoryName()).isEqualTo("corp-ldap");
        assertThat(saved.getAction()).isEqualTo(AuditAction.USER_CREATE);
        assertThat(saved.getTargetDn()).isEqualTo("uid=alice,ou=users,dc=corp,dc=com");
        assertThat(saved.getDetail()).containsKey("attributes");
        assertThat(saved.getOccurredAt()).isNotNull();
    }

    @Test
    void record_exportsSavedEventToSiem() {
        AuthPrincipal principal = new AuthPrincipal(PrincipalType.ADMIN, adminId, "alice");
        when(dirRepo.findById(directoryId)).thenReturn(Optional.of(mockDirectory("corp")));

        auditService.record(principal, directoryId, AuditAction.USER_CREATE,
                "uid=alice,dc=corp", null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(siemExportService).export(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo(AuditAction.USER_CREATE);
    }

    @Test
    void record_doesNotThrow_whenDirLookupFails() {
        AuthPrincipal principal = new AuthPrincipal(PrincipalType.ADMIN, adminId, "bob");
        when(dirRepo.findById(directoryId)).thenReturn(Optional.empty());

        // Should silently swallow the missing-directory case
        auditService.record(principal, directoryId, AuditAction.USER_DELETE,
                "uid=bob,dc=corp", null);

        verify(auditRepo).save(any(AuditEvent.class));
    }

    @Test
    void record_doesNotPropagateException_whenSaveFails() {
        AuthPrincipal principal = new AuthPrincipal(PrincipalType.ADMIN, adminId, "carol");
        DirectoryConnection dc = mockDirectory("dir");
        when(dirRepo.findById(directoryId)).thenReturn(Optional.of(dc));
        doThrow(new RuntimeException("DB down")).when(auditRepo).save(any());

        // Must not throw — audit failures are swallowed
        auditService.record(principal, directoryId, AuditAction.USER_ENABLE,
                "uid=carol,dc=corp", null);

        // SIEM export should NOT be called if save failed
        verify(siemExportService, never()).export(any());
    }

    // ── Changelog event recording ─────────────────────────────────────────────

    @Test
    void recordChangelogEvent_savesEvent_whenNotAlreadyRecorded() {
        String changeNumber = "12345";
        when(auditRepo.existsByDirectoryIdAndChangelogChangeNumber(directoryId, changeNumber))
                .thenReturn(false);

        auditService.recordChangelogEvent(
                directoryId, "corp-ldap",
                "uid=dave,dc=corp", changeNumber,
                Map.of("changeType", "modify"),
                OffsetDateTime.now());

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRepo).save(captor.capture());

        AuditEvent saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(AuditSource.LDAP_CHANGELOG);
        assertThat(saved.getAction()).isEqualTo(AuditAction.LDAP_CHANGE);
        assertThat(saved.getChangelogChangeNumber()).isEqualTo(changeNumber);
        // No ambient scope here → correlation id stays null.
        assertThat(saved.getCorrelationId()).isNull();
    }

    @Test
    void recordChangelogEvent_stampsActiveCorrelationScope() {
        String changeNumber = "12345";
        when(auditRepo.existsByDirectoryIdAndChangelogChangeNumber(directoryId, changeNumber))
                .thenReturn(false);
        UUID pollScope = UUID.randomUUID();

        // Simulate the per-source scope LdapChangelogReader opens around the poll.
        CorrelationContext.withCorrelation(pollScope, () ->
                auditService.recordChangelogEvent(
                        directoryId, "corp-ldap",
                        "uid=dave,dc=corp", changeNumber,
                        Map.of("changeType", "modify"),
                        OffsetDateTime.now()));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRepo).save(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isEqualTo(pollScope);
    }

    @Test
    void recordChangelogEvent_exportsSavedEventToSiem() {
        String changeNumber = "12346";
        when(auditRepo.existsByDirectoryIdAndChangelogChangeNumber(directoryId, changeNumber))
                .thenReturn(false);

        auditService.recordChangelogEvent(
                directoryId, "corp-ldap",
                "uid=dave,dc=corp", changeNumber,
                Map.of("changeType", "modify"),
                OffsetDateTime.now());

        verify(siemExportService).export(any(AuditEvent.class));
    }

    @Test
    void recordChangelogEvent_skips_whenAlreadyRecorded() {
        String changeNumber = "99999";
        when(auditRepo.existsByDirectoryIdAndChangelogChangeNumber(directoryId, changeNumber))
                .thenReturn(true);

        auditService.recordChangelogEvent(
                directoryId, "corp-ldap",
                "uid=eve,dc=corp", changeNumber,
                Map.of("changeType", "add"),
                OffsetDateTime.now());

        verify(auditRepo, never()).save(any());
        verify(siemExportService, never()).export(any());
    }

    // ── isChangelogEventRecorded ──────────────────────────────────────────────

    @Test
    void isChangelogEventRecorded_delegatesToRepository() {
        when(auditRepo.existsByDirectoryIdAndChangelogChangeNumber(directoryId, "42"))
                .thenReturn(true);

        assertThat(auditService.isChangelogEventRecorded(directoryId, "42")).isTrue();
    }

    // ── Detail contributor SPI ────────────────────────────────────────────────

    @Test
    void record_mergesContributorOutput_intoDetail() {
        AuthPrincipal principal = new AuthPrincipal(PrincipalType.ADMIN, adminId, "alice");
        when(dirRepo.findById(directoryId)).thenReturn(Optional.of(mockDirectory("corp")));

        com.ldapportal.core.audit.AuditDetailContributor stamping =
                (dirId, action, dn, base) -> Map.of("vendorIntegration", "ISVA", "softDisable", true);
        AuditService withContributor = new AuditService(
                auditRepo, dirRepo, siemExportService, eventPublisher, java.util.List.of(stamping));

        withContributor.record(principal, directoryId, AuditAction.USER_DELETE,
                "uid=alice,dc=corp", Map.of("attributes", "cn"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRepo).save(captor.capture());
        Map<String, Object> detail = captor.getValue().getDetail();
        assertThat(detail)
                .containsEntry("attributes", "cn")             // caller-supplied preserved
                .containsEntry("vendorIntegration", "ISVA")    // contributor added
                .containsEntry("softDisable", true);
    }

    @Test
    void record_continuesWhenOneContributorThrows() {
        AuthPrincipal principal = new AuthPrincipal(PrincipalType.ADMIN, adminId, "alice");
        when(dirRepo.findById(directoryId)).thenReturn(Optional.of(mockDirectory("corp")));

        com.ldapportal.core.audit.AuditDetailContributor broken =
                (dirId, action, dn, base) -> { throw new RuntimeException("kaboom"); };
        com.ldapportal.core.audit.AuditDetailContributor working =
                (dirId, action, dn, base) -> Map.of("vendorIntegration", "ISVA");
        AuditService withContributors = new AuditService(
                auditRepo, dirRepo, siemExportService, eventPublisher,
                java.util.List.of(broken, working));

        withContributors.record(principal, directoryId, AuditAction.USER_CREATE,
                "uid=alice,dc=corp", null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditRepo).save(captor.capture());
        assertThat(captor.getValue().getDetail())
                .containsEntry("vendorIntegration", "ISVA");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DirectoryConnection mockDirectory(String name) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDisplayName(name);
        return dc;
    }
}
