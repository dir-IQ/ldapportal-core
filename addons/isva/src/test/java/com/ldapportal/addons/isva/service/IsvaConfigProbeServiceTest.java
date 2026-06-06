// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.dto.ProbeResult;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Probe tests against an in-memory UnboundID directory carrying a
 * management DIT seeded with secUser entries. Exercises the real
 * search path (including the server-enforced size limit) rather
 * than mocking the connection, so the size-limit handling is
 * actually verified.
 */
@ExtendWith(MockitoExtension.class)
class IsvaConfigProbeServiceTest {

    @Mock private EncryptionService encryptionService;

    private InMemoryDirectoryServer inMemoryServer;
    private LdapConnectionFactory connectionFactory;
    private IsvaConfigProbeService probeService;
    private DirectoryConnection dir;

    private static final String BASE = "o=acme,c=us";
    private static final String MGMT_BASE = "secAuthority=Default,o=acme,c=us";
    private static final String BIND_DN = "cn=admin,o=acme,c=us";
    private static final String BIND_PASS = "adminpass";

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        // No schema enforcement so the auxiliary secUser objectClass is
        // accepted without loading the fixture schema (the probe keys on
        // the objectClass NAME, not on schema definitions).
        config.setSchema(null);
        inMemoryServer = new InMemoryDirectoryServer(config);
        inMemoryServer.add(new Entry(BASE,
                new Attribute("objectClass", "top", "organization"),
                new Attribute("o", "acme")));
        inMemoryServer.add(new Entry(MGMT_BASE,
                new Attribute("objectClass", "top", "organizationalUnit", "secAuthority"),
                new Attribute("secAuthority", "Default"),
                new Attribute("ou", "Default")));
        inMemoryServer.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService);
        probeService = new IsvaConfigProbeService(connectionFactory);
        dir = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        inMemoryServer.shutDown(true);
    }

    @Test
    void inlineMode_isVacuouslyOk() {
        ProbeResult result = probeService.probe(dir, config(IsvaTopologyMode.INLINE));
        assertThat(result.reachable()).isTrue();
        assertThat(result.sampleSecUserFound()).isTrue();
    }

    @Test
    void linkedMode_multipleSecUsers_reportsFound() {
        // The regression: with MORE than one secUser, a sizeLimit=1
        // search returns SIZE_LIMIT_EXCEEDED. That must read as "found",
        // not "errored / none" — otherwise every real directory (which
        // has many users) falsely reports no sample secUser.
        addSecUser("alice-uuid", "uid=alice,ou=people,dc=x");
        addSecUser("bob-uuid", "uid=bob,ou=people,dc=x");
        addSecUser("carol-uuid", "uid=carol,ou=people,dc=x");

        ProbeResult result = probeService.probe(dir, config(IsvaTopologyMode.LINKED));

        assertThat(result.reachable()).isTrue();
        assertThat(result.sampleSecUserFound()).isTrue();
    }

    @Test
    void linkedMode_singleSecUser_reportsFound() {
        addSecUser("alice-uuid", "uid=alice,ou=people,dc=x");

        ProbeResult result = probeService.probe(dir, config(IsvaTopologyMode.LINKED));

        assertThat(result.reachable()).isTrue();
        assertThat(result.sampleSecUserFound()).isTrue();
    }

    @Test
    void linkedMode_noSecUsers_reportsNotFoundButReachable() {
        ProbeResult result = probeService.probe(dir, config(IsvaTopologyMode.LINKED));

        assertThat(result.reachable()).isTrue();
        assertThat(result.sampleSecUserFound()).isFalse();
        assertThat(result.warnings())
                .anySatisfy(w -> assertThat(w).contains("No `secUser` entries found"));
    }

    @Test
    void linkedMode_unreachableBase_reportsNotReachable() {
        VendorIntegrationIsvaConfig cfg = config(IsvaTopologyMode.LINKED);
        cfg.setManagementDitBaseDn("secAuthority=Missing,o=acme,c=us");

        ProbeResult result = probeService.probe(dir, cfg);

        assertThat(result.reachable()).isFalse();
        assertThat(result.sampleSecUserFound()).isFalse();
    }

    // ── helpers ────────────────────────────────────────────────────

    private void addSecUser(String uuid, String secDn) {
        try {
            inMemoryServer.add(new Entry(
                    "secUUID=" + uuid + "," + MGMT_BASE,
                    new Attribute("objectClass", "top", "account", "secUser"),
                    new Attribute("uid", uuid),
                    new Attribute("secUUID", uuid),
                    new Attribute("secDN", secDn)));
        } catch (Exception e) {
            throw new IllegalStateException("seed secUser failed", e);
        }
    }

    private VendorIntegrationIsvaConfig config(IsvaTopologyMode mode) {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setDirectoryConnectionId(dir.getId());
        cfg.setEnabled(true);
        cfg.setTopologyMode(mode);
        if (mode == IsvaTopologyMode.LINKED) {
            cfg.setManagementDitBaseDn(MGMT_BASE);
        }
        return cfg;
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
        d.setBaseDn(BASE);
        d.setPoolMinSize(1);
        d.setPoolMaxSize(3);
        d.setPoolConnectTimeoutSeconds(5);
        d.setPoolResponseTimeoutSeconds(10);
        d.setPagingSize(100);
        return d;
    }
}
