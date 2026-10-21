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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Mock private com.ldapportal.repository.SyncLinkRepository     syncLinkRepo;
    @Mock private com.ldapportal.repository.SyncSetRepository      syncSetRepo;

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
    void listSummaries_returnsIdentitiesWithoutLoadingBaseDns() {
        when(dirRepo.findAll()).thenReturn(List.of(existing()));

        var result = service.listSummaries();

        org.assertj.core.api.Assertions.assertThat(result).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(result.get(0).id()).isEqualTo(DIR_ID);
        org.assertj.core.api.Assertions.assertThat(result.get(0).displayName())
                .isEqualTo(existing().getDisplayName());
        verify(userBaseDnRepo, never()).findAllByDirectoryIdOrderByDisplayOrderAsc(any());
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

    // ── further roots (multi-root directories) ─────────────────────────────

    @Test
    void updateDirectory_storesAdditionalRoots_trimmed_andReturnsThem() {
        var resp = service.updateDirectory(DIR_ID, requestWithRoots(List.of(" o=partners ", "", "secAuthority=Default")));

        assertThat(resp.additionalBaseDns()).containsExactly("o=partners", "secAuthority=Default");
    }

    @Test
    void updateDirectory_addingARoot_warnsAboutSyncSetsWithBlankBases() {
        com.ldapportal.entity.SyncLink asSource = new com.ldapportal.entity.SyncLink();
        asSource.setId(UUID.randomUUID());
        com.ldapportal.entity.SyncLink asTarget = new com.ldapportal.entity.SyncLink();
        asTarget.setId(UUID.randomUUID());
        com.ldapportal.entity.SyncSet blankScope = new com.ldapportal.entity.SyncSet();
        blankScope.setName("people");
        com.ldapportal.entity.SyncSet scoped = new com.ldapportal.entity.SyncSet();
        scoped.setName("partners");
        scoped.setObjectScopeBaseDn("o=partners");
        com.ldapportal.entity.SyncSet blankTarget = new com.ldapportal.entity.SyncSet();
        blankTarget.setName("inbound");
        when(syncLinkRepo.findAllBySourceDirId(DIR_ID)).thenReturn(List.of(asSource));
        when(syncLinkRepo.findAllByTargetDirId(DIR_ID)).thenReturn(List.of(asTarget));
        when(syncSetRepo.findAllByLinkId(asSource.getId())).thenReturn(List.of(blankScope, scoped));
        when(syncSetRepo.findAllByLinkId(asTarget.getId())).thenReturn(List.of(blankTarget));

        var resp = service.updateDirectory(DIR_ID, requestWithRoots(List.of("o=partners")));

        // Saved anyway; each blank-base set is named once.
        assertThat(resp.additionalBaseDns()).containsExactly("o=partners");
        assertThat(resp.warnings()).hasSize(2);
        assertThat(resp.warnings().get(0)).contains("\"people\"", "source scope base DN");
        assertThat(resp.warnings().get(1)).contains("\"inbound\"", "target base DN");
    }

    @Test
    void updateDirectory_withoutNewRoots_doesNotLookForSyncSets() {
        var resp = service.updateDirectory(DIR_ID, matchingRequest("ldap.example.com"));

        assertThat(resp.warnings()).isEmpty();
        verify(syncLinkRepo, never()).findAllBySourceDirId(any());
        verify(syncLinkRepo, never()).findAllByTargetDirId(any());
    }

    @Test
    void updateDirectory_rejectsARootNestedWithAnother() {
        assertThatThrownBy(() -> service.updateDirectory(DIR_ID,
                requestWithRoots(List.of("ou=people,dc=example,dc=com"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nested");
        assertThatThrownBy(() -> service.updateDirectory(DIR_ID,
                requestWithRoots(List.of("o=partners", "DC=Example,DC=Com"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateDirectory(DIR_ID,
                requestWithRoots(List.of("o=partners", "ou=x,o=partners"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateDirectory_rejectsAnInvalidRootDn() {
        assertThatThrownBy(() -> service.updateDirectory(DIR_ID, requestWithRoots(List.of("not a dn"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exportAll_includesAdditionalRoots_andOmitsThemWhenThereAreNone() {
        DirectoryConnection multi = existing();
        multi.setAdditionalBaseDns(new java.util.ArrayList<>(List.of("o=partners")));
        when(dirRepo.findAll()).thenReturn(List.of(multi));
        assertThat(service.exportAll().get(0).request().additionalBaseDns()).containsExactly("o=partners");

        when(dirRepo.findAll()).thenReturn(List.of(existing()));
        assertThat(service.exportAll().get(0).request().additionalBaseDns()).isNull();
    }

    private static DirectoryConnectionRequest requestWithRoots(List<String> roots) {
        return new DirectoryConnectionRequest(
                DirectoryType.GENERIC, "Corp LDAP", "ldap.example.com", 389, SslMode.NONE,
                false, null, "cn=admin,dc=example,dc=com", null,
                "dc=example,dc=com", 500, 1, 10, 5, 30, 300,
                null, null, null, null, null, true,
                false, null, null, null, null,
                null, null,
                null, null,
                null, null, null, "https://graph.microsoft.com",
                null, null, roots);
    }

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
                null, auditSlug, null);
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
                null, null, null);
    }
}
