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
import com.ldapportal.addons.isva.entity.IsvaRdnValueSource;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.schema.Schema;
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
        // This server runs without an enforced schema (setSchema(null)),
        // so getSchema() returns the default standard schema — which does
        // not define secUser. Schema validation therefore reports invalid
        // with the "objectClass not defined" warning. (The unknown/null
        // verdict is reserved for servers that refuse to return a schema
        // at all; an in-memory server can't simulate that.)
        assertThat(result.schemaValid()).isFalse();
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("objectClass `secUser`").contains("not defined"));
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

    // ── schema validation ──────────────────────────────────────────

    @Test
    void schemaValid_whenObjectClassesAndRdnAttributePermitted() throws Exception {
        // Customer pattern: principalName RDN, contributed by eUser.
        VendorIntegrationIsvaConfig cfg = config(IsvaTopologyMode.LINKED);
        cfg.setSecuserObjectClasses(java.util.List.of("secUser", "eUser"));
        cfg.setSecuserRdnAttribute("principalName");
        cfg.setSecuserRdnValueSource(IsvaRdnValueSource.UID);

        ProbeResult result = probeWithSchema(customSchema(true), cfg);

        assertThat(result.schemaValid()).isTrue();
        assertThat(result.warnings())
                .noneMatch(w -> w.contains("not defined") || w.contains("not permitted"));
    }

    @Test
    void schemaInvalid_whenObjectClassMissingFromServerSchema() throws Exception {
        // eUser is NOT in this server's schema.
        VendorIntegrationIsvaConfig cfg = config(IsvaTopologyMode.LINKED);
        cfg.setSecuserObjectClasses(java.util.List.of("secUser", "eUser"));
        cfg.setSecuserRdnAttribute("secUUID");

        ProbeResult result = probeWithSchema(customSchema(false), cfg);

        assertThat(result.schemaValid()).isFalse();
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("objectClass `eUser`").contains("not defined"));
    }

    @Test
    void schemaInvalid_whenRdnAttributeNotPermittedByAnyConfiguredClass() throws Exception {
        // principalName exists in the schema but only eUser permits it;
        // configuring secUser alone leaves the RDN unsatisfiable.
        VendorIntegrationIsvaConfig cfg = config(IsvaTopologyMode.LINKED);
        cfg.setSecuserObjectClasses(java.util.List.of("secUser"));
        cfg.setSecuserRdnAttribute("principalName");

        ProbeResult result = probeWithSchema(customSchema(true), cfg);

        assertThat(result.schemaValid()).isFalse();
        assertThat(result.warnings()).anySatisfy(w ->
                assertThat(w).contains("principalName").contains("not permitted"));
    }

    // ── helpers ────────────────────────────────────────────────────

    /**
     * A schema extending the UnboundID default standard schema with the
     * IVIA sec* attributes + secUser class, and (when {@code withEUser})
     * the eUser class that contributes {@code principalName}.
     */
    private static Schema customSchema(boolean withEUser) throws Exception {
        Entry e = Schema.getDefaultStandardSchema().getSchemaEntry().duplicate();
        e.addAttribute("attributeTypes",
                "( 1.3.6.1.4.1.99999.1.1.1 NAME 'secUUID' EQUALITY caseIgnoreMatch "
                        + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 SINGLE-VALUE )");
        e.addAttribute("attributeTypes",
                "( 1.3.6.1.4.1.99999.1.1.2 NAME 'secLogin' EQUALITY caseIgnoreMatch "
                        + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )");
        e.addAttribute("attributeTypes",
                "( 1.3.6.1.4.1.99999.1.1.3 NAME 'secDN' EQUALITY distinguishedNameMatch "
                        + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.12 )");
        e.addAttribute("attributeTypes",
                "( 1.3.6.1.4.1.99999.1.1.4 NAME 'principalName' EQUALITY caseIgnoreMatch "
                        + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )");
        e.addAttribute("objectClasses",
                "( 1.3.6.1.4.1.99999.1.2.1 NAME 'secUser' SUP top AUXILIARY "
                        + "MAY ( secLogin $ secDN $ secUUID ) )");
        if (withEUser) {
            e.addAttribute("objectClasses",
                    "( 1.3.6.1.4.1.99999.1.2.2 NAME 'eUser' SUP top AUXILIARY "
                            + "MAY ( principalName ) )");
        }
        return new Schema(e);
    }

    /**
     * Spin up a throwaway schema-enabled in-memory server, run the probe
     * against it, and tear it down. Schema validation runs independently
     * of reachability, so only the base entry needs seeding.
     */
    private ProbeResult probeWithSchema(Schema schema, VendorIntegrationIsvaConfig cfg)
            throws Exception {
        InMemoryDirectoryServerConfig c = new InMemoryDirectoryServerConfig(BASE);
        c.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        c.setSchema(schema);
        InMemoryDirectoryServer server = new InMemoryDirectoryServer(c);
        server.add(new Entry(BASE,
                new Attribute("objectClass", "top", "organization"),
                new Attribute("o", "acme")));
        server.startListening();
        LdapConnectionFactory factory = new LdapConnectionFactory(encryptionService);
        try {
            DirectoryConnection d = buildDcForPort(server.getListenPort());
            cfg.setDirectoryConnectionId(d.getId());
            if (cfg.getTopologyMode() == IsvaTopologyMode.LINKED) {
                cfg.setManagementDitBaseDn(BASE);
            }
            return new IsvaConfigProbeService(factory).probe(d, cfg);
        } finally {
            factory.closeAll();
            server.shutDown(true);
        }
    }

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
        return buildDcForPort(inMemoryServer.getListenPort());
    }

    private DirectoryConnection buildDcForPort(int port) {
        DirectoryConnection d = new DirectoryConnection();
        d.setId(UUID.randomUUID());
        d.setDisplayName("test-ldap");
        d.setDirectoryType(DirectoryType.OPENLDAP);
        d.setHost("localhost");
        d.setPort(port);
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
