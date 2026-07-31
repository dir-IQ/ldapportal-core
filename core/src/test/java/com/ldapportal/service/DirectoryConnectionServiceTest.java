// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.core.directory.event.DirectoryConnectionSavedEvent;
import com.ldapportal.dto.directory.DirectoryConnectionRequest;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.repository.AuditDataSourceRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.DirectoryGroupBaseDnRepository;
import com.ldapportal.repository.DirectoryUserBaseDnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focuses on the idempotency of {@link DirectoryConnectionService#updateDirectory}:
 * an unchanged re-apply must not evict the connection pool or trigger a
 * capability re-probe, while a connection-affecting change must do both.
 */
@ExtendWith(MockitoExtension.class)
class DirectoryConnectionServiceTest {

    @Mock private DirectoryConnectionRepository  dirRepo;
    @Mock private DirectoryUserBaseDnRepository  userBaseDnRepo;
    @Mock private DirectoryGroupBaseDnRepository groupBaseDnRepo;
    @Mock private AuditDataSourceRepository      auditSourceRepo;
    @Mock private EncryptionService              encryptionService;
    @Mock private LdapConnectionFactory          connectionFactory;
    @Mock private ApplicationEventPublisher      eventPublisher;
    @Mock private com.ldapportal.core.entitlement.UsageLimitService usageLimitService;
    @Mock private com.ldapportal.directory.DirectoryProviderRegistry providerRegistry;

    @InjectMocks private DirectoryConnectionService service;

    private static final UUID DIR_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(dirRepo.findById(DIR_ID)).thenReturn(Optional.of(existing()));
        lenient().when(dirRepo.saveAndFlush(any(DirectoryConnection.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userBaseDnRepo.findAllByDirectoryIdOrderByDisplayOrderAsc(DIR_ID))
                .thenReturn(List.of());
        lenient().when(groupBaseDnRepo.findAllByDirectoryIdOrderByDisplayOrderAsc(DIR_ID))
                .thenReturn(List.of());
    }

    @Test
    void updateDirectory_noConnectionChange_skipsEvictAndProbe() {
        service.updateDirectory(DIR_ID, matchingRequest("ldap.example.com"));

        verify(connectionFactory, never()).evict(any());
        verify(eventPublisher, never()).publishEvent(any(DirectoryConnectionSavedEvent.class));
    }

    @Test
    void updateDirectory_hostChange_evictsAndProbes() {
        service.updateDirectory(DIR_ID, matchingRequest("new-host.example.com"));

        verify(connectionFactory).evict(DIR_ID);
        verify(eventPublisher).publishEvent(any(DirectoryConnectionSavedEvent.class));
        // saveAndFlush (not save) so the response/ETag reflects the @Version bump.
        verify(dirRepo).saveAndFlush(any(DirectoryConnection.class));
    }

    @Test
    void updateDirectory_withAuditSourceSlug_resolvesLinkBySlug() {
        com.ldapportal.entity.AuditDataSource audit = new com.ldapportal.entity.AuditDataSource();
        UUID auditId = UUID.randomUUID();
        audit.setId(auditId);
        audit.setSlug("dsee-audit");
        when(auditSourceRepo.findBySlug("dsee-audit")).thenReturn(Optional.of(audit));

        var resp = service.updateDirectory(DIR_ID, requestWithAuditSlug("dsee-audit"));

        // Linked by slug — no UUID needed from the caller.
        org.assertj.core.api.Assertions.assertThat(resp.auditDataSourceId()).isEqualTo(auditId);
    }

    @Test
    void updateDirectory_unknownAuditSourceSlug_rejected() {
        when(auditSourceRepo.findBySlug("nope")).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.updateDirectory(DIR_ID, requestWithAuditSlug("nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void exportAll_linkedAuditSource_emitsSlug_andClearsId() {
        com.ldapportal.entity.AuditDataSource audit = new com.ldapportal.entity.AuditDataSource();
        audit.setId(UUID.randomUUID());
        audit.setSlug("dsee-audit");
        DirectoryConnection dc = existing();
        dc.setAuditDataSource(audit);
        when(dirRepo.findAll()).thenReturn(List.of(dc));

        List<DirectoryConnectionService.DirectoryExport> exports = service.exportAll();

        org.assertj.core.api.Assertions.assertThat(exports).hasSize(1);
        DirectoryConnectionRequest req = exports.get(0).request();
        org.assertj.core.api.Assertions.assertThat(req.auditDataSourceSlug()).isEqualTo("dsee-audit");
        org.assertj.core.api.Assertions.assertThat(req.auditDataSourceId()).isNull();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** {@link #matchingRequest} with the audit-source link expressed by slug. */
    private static DirectoryConnectionRequest requestWithAuditSlug(String auditSlug) {
        return new DirectoryConnectionRequest(
                DirectoryType.GENERIC, "Corp LDAP", "ldap.example.com", 389, SslMode.NONE,
                false, null, "cn=admin,dc=example,dc=com", null,
                "dc=example,dc=com", 500, 1, 10, 5, 30, 300,
                null, null, null, null, null, true,
                false, null, null, null, null,
                null, null,
                null, null,
                null, null, null, "https://graph.microsoft.com",
                null, auditSlug);
    }

    private static DirectoryConnection existing() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(DIR_ID);
        dc.setVersion(0L);
        dc.setSlug("corp");
        dc.setDirectoryType(DirectoryType.GENERIC);
        dc.setDisplayName("Corp LDAP");
        dc.setHost("ldap.example.com");
        dc.setPort(389);
        dc.setSslMode(SslMode.NONE);
        dc.setTrustAllCerts(false);
        dc.setBindDn("cn=admin,dc=example,dc=com");
        dc.setBindPasswordEncrypted("enc-existing");
        dc.setBaseDn("dc=example,dc=com");
        dc.setPagingSize(500);
        dc.setPoolMinSize(1);
        dc.setPoolMaxSize(10);
        dc.setPoolConnectTimeoutSeconds(5);
        dc.setPoolResponseTimeoutSeconds(30);
        dc.setEnabled(true);
        dc.setGraphEndpoint("https://graph.microsoft.com");
        return dc;
    }

    /**
     * A request whose connection-affecting fields mirror {@link #existing()}
     * except {@code host}, so passing the same host is a true no-op and a
     * different host is a connection change. No {@code bindPassword} is sent,
     * so the secret is left untouched.
     */
    private static DirectoryConnectionRequest matchingRequest(String host) {
        return new DirectoryConnectionRequest(
                DirectoryType.GENERIC, "Corp LDAP", host, 389, SslMode.NONE,
                false, null, "cn=admin,dc=example,dc=com", null,
                "dc=example,dc=com", 500, 1, 10, 5, 30, 300,
                null, null, null, null, null, true,
                false, null, null, null, null,
                null, null,
                null, null,
                null, null, null, "https://graph.microsoft.com",
                null, null);
    }
}
