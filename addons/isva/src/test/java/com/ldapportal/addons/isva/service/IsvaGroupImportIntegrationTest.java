// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.IsvaGroupRegistry;
import com.ldapportal.addons.isva.IsvaGroupShapeCheck;
import com.ldapportal.addons.isva.dto.IsvaGroupRegistrations;
import com.ldapportal.addons.isva.dto.IsvaGroupStatus;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.core.provisioning.PlanExecutor;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.AuditService;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.SearchResultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

/**
 * "Import into IVIA" against an in-memory directory laid out like a real
 * full-mode IVIA install: the management DIT {@code secAuthority=Default,…}
 * with {@code cn=Users} and {@code cn=Groups} below it. An import writes the
 * same secGroup registration entry {@code pdadmin group import} does, and
 * leaves the group itself untouched.
 */
@ExtendWith(MockitoExtension.class)
class IsvaGroupImportIntegrationTest {

    private static final String BASE_DN = "c=us";
    private static final String GROUPS_OU = "o=groups,c=us";
    private static final String GROUP_DN = "cn=Engineers,o=groups,c=us";
    private static final String DIT = "secAuthority=Default,cn=treasury";
    private static final String GROUP_CONTAINER = "cn=Groups," + DIT;
    private static final String BIND_DN = "cn=admin,c=us";
    private static final String BIND_PASS = "adminpass";

    @Mock private EncryptionService encryptionService;
    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private VendorIntegrationIsvaConfigRepository configRepo;
    @Mock private AuditService auditService;

    private InMemoryDirectoryServer server;
    private LdapConnectionFactory connectionFactory;
    private IsvaGroupRegistry registry;
    private IsvaGroupService service;
    private DirectoryConnection dir;
    private VendorIntegrationIsvaConfig cfg;
    private final AuthPrincipal admin = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "admin");

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN, "cn=treasury");
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        config.setSchema(null);
        server = new InMemoryDirectoryServer(config);
        server.add(new Entry(BASE_DN, new Attribute("objectClass", "top", "country"), new Attribute("c", "us")));
        server.add(new Entry(GROUPS_OU, new Attribute("objectClass", "top", "organization"),
                new Attribute("o", "groups")));
        server.add(new Entry(GROUP_DN, new Attribute("objectClass", "top", "groupOfUniqueNames"),
                new Attribute("cn", "Engineers"), new Attribute("uniqueMember", "cn=admin,c=us")));
        server.add(new Entry("cn=treasury", new Attribute("objectClass", "top", "container"),
                new Attribute("cn", "treasury")));
        server.add(new Entry(DIT, new Attribute("objectClass", "top", "secAuthorityInfo"),
                new Attribute("secAuthority", "Default")));
        server.add(new Entry("cn=Users," + DIT, new Attribute("objectClass", "top", "container"),
                new Attribute("cn", "Users")));
        server.add(new Entry(GROUP_CONTAINER, new Attribute("objectClass", "top", "container"),
                new Attribute("cn", "Groups")));
        server.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService);
        registry = new IsvaGroupRegistry(connectionFactory);
        service = new IsvaGroupService(dirRepo, configRepo, registry, new PlanExecutor(connectionFactory),
                connectionFactory, auditService);

        dir = new DirectoryConnection();
        dir.setId(UUID.randomUUID());
        dir.setDisplayName("ivia-ldap");
        dir.setDirectoryType(DirectoryType.IBM_DIRECTORY_SERVER);
        dir.setHost("localhost");
        dir.setPort(server.getListenPort());
        dir.setSslMode(SslMode.NONE);
        dir.setBindDn(BIND_DN);
        dir.setBindPasswordEncrypted("enc-placeholder");
        dir.setBaseDn(BASE_DN);
        dir.setPoolMinSize(1);
        dir.setPoolMaxSize(3);
        dir.setPoolConnectTimeoutSeconds(5);
        dir.setPoolResponseTimeoutSeconds(10);
        dir.setPagingSize(100);

        cfg = new VendorIntegrationIsvaConfig();
        cfg.setDirectoryConnectionId(dir.getId());
        cfg.setEnabled(true);
        cfg.setTopologyMode(IsvaTopologyMode.LINKED);
        cfg.setManagementDitBaseDn(DIT);
        lenient().when(dirRepo.findById(dir.getId())).thenReturn(Optional.of(dir));
        lenient().when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        server.shutDown(true);
    }

    @Test
    void import_writesTheSameRegistrationEntryAsPdadmin_andLeavesTheGroupAlone() throws Exception {
        Entry before = server.getEntry(GROUP_DN);

        IsvaGroupStatus status = service.importGroup(dir.getId(), GROUP_DN, null, admin);

        String regDn = "cn=Engineers," + GROUP_CONTAINER;
        assertThat(status.registered()).isTrue();
        assertThat(status.registrationDn()).isEqualTo(regDn);
        SearchResultEntry reg = server.getConnection().getEntry(regDn);
        assertThat(reg.getAttributeValues("objectClass")).containsExactlyInAnyOrder("top", "secGroup");
        assertThat(reg.getAttributeValue("cn")).isEqualTo("Engineers");
        assertThat(reg.getAttributeValue("secAuthority")).isEqualTo("Default");
        assertThat(reg.getAttributeValue("secDN")).isEqualTo(GROUP_DN);
        assertThat(reg.getAttributeValue("secHasPolicy")).isEqualTo("FALSE");
        assertThat(UUID.fromString(reg.getAttributeValue("secUUID"))).isNotNull();
        assertThat(server.getEntry(GROUP_DN)).isEqualTo(before);
        verify(auditService).record(eq(admin), eq(dir.getId()), eq(AuditAction.GROUP_UPDATE), eq(GROUP_DN),
                eq(Map.of("source", "ivia", "ivia_op", "group_import",
                        "iviaGroupName", "Engineers", "registrationDn", regDn)));
    }

    @Test
    void status_beforeAndAfter_andTheBatchQuery() {
        assertThat(service.status(dir.getId(), GROUP_DN))
                .isEqualTo(new IsvaGroupStatus(false, null, true, "Engineers"));

        service.importGroup(dir.getId(), GROUP_DN, "Eng Team", admin);

        assertThat(service.status(dir.getId(), GROUP_DN).registrationDn())
                .isEqualTo("cn=Eng Team," + GROUP_CONTAINER);
        // Matched however the DN is spelled; returned as sent.
        IsvaGroupRegistrations regs = service.registrations(dir.getId(),
                List.of("CN=Engineers, O=Groups, C=US", "cn=Other,o=groups,c=us"), dn -> true);
        assertThat(regs).isEqualTo(new IsvaGroupRegistrations(true, true, List.of("CN=Engineers, O=Groups, C=US")));
        // A DN the caller may not see is left out.
        assertThat(service.registrations(dir.getId(), List.of(GROUP_DN), dn -> false).registered()).isEmpty();
    }

    @Test
    void import_refusesTwice_aTakenName_aNonGroup_andAMissingGroup() throws Exception {
        service.importGroup(dir.getId(), GROUP_DN, null, admin);
        assertThatThrownBy(() -> service.importGroup(dir.getId(), GROUP_DN, null, admin))
                .isInstanceOfSatisfying(IsvaAccountRefusalException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ivia_group_already_imported"));

        server.add(new Entry("o=other,c=us", new Attribute("objectClass", "top", "organization"),
                new Attribute("o", "other")));
        server.add(new Entry("cn=Engineers,o=other,c=us", new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", "Engineers")));
        assertThatThrownBy(() -> service.importGroup(dir.getId(), "cn=Engineers,o=other,c=us", null, admin))
                .isInstanceOfSatisfying(IsvaAccountRefusalException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ivia_group_name_taken"));

        server.add(new Entry("uid=bob,o=groups,c=us", new Attribute("objectClass", "top", "inetOrgPerson"),
                new Attribute("uid", "bob"), new Attribute("cn", "Bob"), new Attribute("sn", "B")));
        assertThatThrownBy(() -> service.importGroup(dir.getId(), "uid=bob,o=groups,c=us", null, admin))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.importGroup(dir.getId(), "cn=nope,o=groups,c=us", null, admin))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void import_withoutAManagementDit_isRefused_andStatusSaysItCantImport() {
        cfg.setManagementDitBaseDn(null);

        assertThat(service.status(dir.getId(), GROUP_DN).canImport()).isFalse();
        assertThatThrownBy(() -> service.importGroup(dir.getId(), GROUP_DN, null, admin))
                .isInstanceOfSatisfying(IsvaAccountRefusalException.class,
                        e -> assertThat(e.getCode()).isEqualTo("ivia_no_group_container"));
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void directoryWithoutIvia_answersDisabled_toTheBatchQuery() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.empty());

        assertThat(service.registrations(dir.getId(), List.of(GROUP_DN), dn -> true))
                .isEqualTo(new IsvaGroupRegistrations(false, false, List.of()));
    }

    @Test
    void requireSecGroupCheck_acceptsAnImportedGroup_withoutTheObjectClassOnTheGroup() {
        IsvaGroupShapeCheck check = new IsvaGroupShapeCheck(connectionFactory, registry);
        assertThat(check.isImportedIntoIvia(dir, cfg, GROUP_DN)).contains(false);

        service.importGroup(dir.getId(), GROUP_DN, null, admin);

        assertThat(check.isImportedIntoIvia(dir, cfg, GROUP_DN)).contains(true);
    }

    @Test
    void requireSecGroupCheck_stillAcceptsTheObjectClassOnTheGroup_whenThereIsNoContainer() throws Exception {
        server.add(new Entry("cn=legacy,o=groups,c=us", new Attribute("objectClass", "top", "groupOfNames", "secGroup"),
                new Attribute("cn", "legacy")));
        cfg.setManagementDitBaseDn(null);
        IsvaGroupShapeCheck check = new IsvaGroupShapeCheck(connectionFactory, registry);

        assertThat(check.isImportedIntoIvia(dir, cfg, "cn=legacy,o=groups,c=us")).contains(true);
        assertThat(check.isImportedIntoIvia(dir, cfg, GROUP_DN)).contains(false);
    }
}
