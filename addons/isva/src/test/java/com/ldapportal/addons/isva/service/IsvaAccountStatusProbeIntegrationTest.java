// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.IsvaLinkedUserLookup;
import com.ldapportal.addons.isva.IsvaSecUserPlans;
import com.ldapportal.addons.isva.dto.IsvaAccountStatus;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Probe behaviour against an in-memory UnboundID directory. The probe's
 * topology-aware existence + lifecycle snapshot is the gap v1 of the
 * design didn't close; this suite pins both paths:
 *
 * <ul>
 *   <li>Inline mode — presence is "demographic carries the secUser
 *       objectClass." A demographic-only entry shows as orphaned.</li>
 *   <li>Linked mode — presence is "paired secUser entry exists under
 *       the management DIT." Demographic-only or pair-broken shows
 *       as orphaned.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class IsvaAccountStatusProbeIntegrationTest {

    @Mock private EncryptionService encryptionService;

    private InMemoryDirectoryServer inMemoryServer;
    private LdapConnectionFactory connectionFactory;
    private IsvaAccountStatusProbe probe;
    private DirectoryConnection dir;

    private static final String BASE_DN = "dc=example,dc=com";
    private static final String PEOPLE_OU = "ou=people,dc=example,dc=com";
    private static final String MGMT_BASE = "secAuthority=Default,o=acme,c=us";
    private static final String BIND_DN = "cn=admin,dc=example,dc=com";
    private static final String BIND_PASS = "adminpass";

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(
                BASE_DN, "o=acme,c=us");
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        config.setSchema(null);
        inMemoryServer = new InMemoryDirectoryServer(config);
        inMemoryServer.add(new Entry(BASE_DN,
                new Attribute("objectClass", "top", "domain"),
                new Attribute("dc", "example")));
        inMemoryServer.add(new Entry(PEOPLE_OU,
                new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "people")));
        inMemoryServer.add(new Entry("o=acme,c=us",
                new Attribute("objectClass", "top", "organization"),
                new Attribute("o", "acme")));
        inMemoryServer.add(new Entry(MGMT_BASE,
                new Attribute("objectClass", "top", "organizationalUnit", "secAuthority"),
                new Attribute("secAuthority", "Default"),
                new Attribute("ou", "Default")));
        inMemoryServer.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService);
        IsvaLinkedUserLookup lookup = new IsvaLinkedUserLookup(connectionFactory);
        probe = new IsvaAccountStatusProbe(lookup, connectionFactory);
        dir = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        inMemoryServer.shutDown(true);
    }

    // ── inline ──────────────────────────────────────────────────

    @Test
    void inline_demographicWithSecUserOverlay_isPresent_withLifecycleFields() throws Exception {
        String dn = "uid=alice," + PEOPLE_OU;
        inMemoryServer.add(new Entry(dn,
                new Attribute("objectClass", "inetOrgPerson", "secUser"),
                new Attribute("uid", "alice"),
                new Attribute("cn", "Alice"),
                new Attribute("sn", "A"),
                new Attribute("secLogin", "alice"),
                new Attribute("secAuthority", "Default"),
                new Attribute("secAcctValid", "TRUE"),
                new Attribute("secPwdValid", "TRUE"),
                new Attribute("secValidUntil",
                        IsvaSecUserPlans.generalizedTime(Instant.now().plusSeconds(86_400))),
                new Attribute("secPwdLastChanged",
                        IsvaSecUserPlans.generalizedTime(Instant.now().minusSeconds(3600)))));

        IsvaAccountStatus status = probe.probe(dir, inlineConfig(), dn);

        assertThat(status.linked()).isTrue();
        assertThat(status.orphaned()).isFalse();
        assertThat(status.topology()).isEqualTo(IsvaTopologyMode.INLINE);
        assertThat(status.acctValid()).isTrue();
        assertThat(status.pwdValid()).isTrue();
        assertThat(status.authority()).isEqualTo("Default");
        // Inline mode → no separate secUser DN.
        assertThat(status.secUserDn()).isNull();
        assertThat(status.validUntil()).isNotNull();
    }

    @Test
    void inline_demographicWithoutSecUserObjectClass_isOrphaned() throws Exception {
        String dn = "uid=bob," + PEOPLE_OU;
        inMemoryServer.add(new Entry(dn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "bob"),
                new Attribute("cn", "Bob"), new Attribute("sn", "B")));

        IsvaAccountStatus status = probe.probe(dir, inlineConfig(), dn);

        assertThat(status.linked()).isFalse();
        assertThat(status.orphaned()).isTrue();
        assertThat(status.topology()).isEqualTo(IsvaTopologyMode.INLINE);
    }

    @Test
    void inline_suspendedAccount_acctValidFalseSurfacesInStatus() throws Exception {
        String dn = "uid=carol," + PEOPLE_OU;
        inMemoryServer.add(new Entry(dn,
                new Attribute("objectClass", "inetOrgPerson", "secUser"),
                new Attribute("uid", "carol"),
                new Attribute("cn", "Carol"), new Attribute("sn", "C"),
                new Attribute("secAcctValid", "FALSE"),
                new Attribute("secPwdValid", "TRUE")));

        IsvaAccountStatus status = probe.probe(dir, inlineConfig(), dn);

        assertThat(status.linked()).isTrue();
        assertThat(status.acctValid()).isFalse();
    }

    // ── linked ──────────────────────────────────────────────────

    @Test
    void linked_pairedSecUserExists_isPresent_withSecUserDn() throws Exception {
        String dn = "uid=dan," + PEOPLE_OU;
        String secUserDn = "secUUID=dan-uuid," + MGMT_BASE;
        inMemoryServer.add(new Entry(dn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "dan"),
                new Attribute("cn", "Dan"), new Attribute("sn", "D")));
        inMemoryServer.add(new Entry(secUserDn,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "dan-uuid"),
                new Attribute("secDN", dn),
                new Attribute("secLogin", "dan"),
                new Attribute("secAuthority", "Default"),
                new Attribute("secAcctValid", "TRUE"),
                new Attribute("secPwdValid", "TRUE")));

        IsvaAccountStatus status = probe.probe(dir, linkedConfig(), dn);

        assertThat(status.linked()).isTrue();
        assertThat(status.orphaned()).isFalse();
        assertThat(status.topology()).isEqualTo(IsvaTopologyMode.LINKED);
        assertThat(status.secUserDn()).isEqualToIgnoringCase(secUserDn);
        assertThat(status.acctValid()).isTrue();
    }

    @Test
    void linked_demographicWithoutPairedSecUser_isOrphaned() throws Exception {
        String dn = "uid=erin," + PEOPLE_OU;
        inMemoryServer.add(new Entry(dn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "erin"),
                new Attribute("cn", "Erin"), new Attribute("sn", "E")));

        IsvaAccountStatus status = probe.probe(dir, linkedConfig(), dn);

        assertThat(status.linked()).isFalse();
        assertThat(status.orphaned()).isTrue();
        assertThat(status.topology()).isEqualTo(IsvaTopologyMode.LINKED);
        assertThat(status.secUserDn()).isNull();
    }

    // ── 404 on missing demographic ────────────────────────────

    @Test
    void unknownDemographicDn_throws_ResourceNotFound() {
        assertThatThrownBy(() -> probe.probe(dir, inlineConfig(),
                "uid=ghost," + PEOPLE_OU))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Identity not found");
    }

    @Test
    void resolveUid_returnsUidValue() throws Exception {
        String dn = "uid=frank," + PEOPLE_OU;
        inMemoryServer.add(new Entry(dn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "frank"),
                new Attribute("cn", "Frank"), new Attribute("sn", "F")));

        assertThat(probe.resolveUid(dir, dn)).isEqualTo("frank");
    }

    @Test
    void resolveUid_missingDn_throwsResourceNotFound() {
        assertThatThrownBy(() -> probe.resolveUid(dir, "uid=ghost," + PEOPLE_OU))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── fixtures ──────────────────────────────────────────────

    private VendorIntegrationIsvaConfig inlineConfig() {
        VendorIntegrationIsvaConfig c = new VendorIntegrationIsvaConfig();
        c.setDirectoryConnectionId(dir.getId());
        c.setEnabled(true);
        c.setTopologyMode(IsvaTopologyMode.INLINE);
        c.setSecAuthority("Default");
        return c;
    }

    private VendorIntegrationIsvaConfig linkedConfig() {
        VendorIntegrationIsvaConfig c = new VendorIntegrationIsvaConfig();
        c.setDirectoryConnectionId(dir.getId());
        c.setEnabled(true);
        c.setTopologyMode(IsvaTopologyMode.LINKED);
        c.setManagementDitBaseDn(MGMT_BASE);
        c.setSecuserRdnAttribute("secUUID");
        c.setSecAuthority("Default");
        return c;
    }

    private DirectoryConnection buildDc() {
        DirectoryConnection d = new DirectoryConnection();
        d.setId(UUID.randomUUID());
        d.setDisplayName("test-ldap");
        d.setDirectoryType(DirectoryType.OPENLDAP);
        d.setHost("localhost");
        d.setPort(inMemoryServer.getListenPort());
        d.setSslMode(SslMode.NONE);
        d.setTrustAllCerts(false);
        d.setBindDn(BIND_DN);
        d.setBindPasswordEncrypted("enc-placeholder");
        d.setBaseDn(BASE_DN);
        d.setPoolMinSize(1);
        d.setPoolMaxSize(3);
        d.setPoolConnectTimeoutSeconds(5);
        d.setPoolResponseTimeoutSeconds(10);
        d.setPagingSize(100);
        return d;
    }
}
