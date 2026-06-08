// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.core.reports.ReportData;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * End-to-end tests for the orphaned-IVIA-accounts report against an in-memory
 * UnboundID directory carrying both a demographic OU and a management DIT with
 * secUser entries linked via {@code secDN}.
 */
@ExtendWith(MockitoExtension.class)
class IsvaOrphanedAccountsReportProviderTest {

    @Mock private EncryptionService encryptionService;
    @Mock private VendorIntegrationIsvaConfigRepository configRepo;

    private InMemoryDirectoryServer inMemoryServer;
    private LdapConnectionFactory connectionFactory;
    private IsvaOrphanedAccountsReportProvider provider;
    private DirectoryConnection dir;

    private static final String DEMOGRAPHIC_BASE = "dc=example,dc=com";
    private static final String PEOPLE_OU = "ou=people,dc=example,dc=com";
    private static final String MGMT_BASE = "secAuthority=Default,o=acme,c=us";
    private static final String BIND_DN = "cn=admin,dc=example,dc=com";
    private static final String BIND_PASS = "adminpass";

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(
                DEMOGRAPHIC_BASE, "o=acme,c=us");
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        config.setSchema(null);
        inMemoryServer = new InMemoryDirectoryServer(config);
        inMemoryServer.add(new Entry(DEMOGRAPHIC_BASE,
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
        provider = new IsvaOrphanedAccountsReportProvider(configRepo, connectionFactory);
        dir = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        inMemoryServer.shutDown(true);
    }

    @Test
    void reportId_isStable() {
        assertThat(provider.reportId()).isEqualTo("ORPHANED_IVIA_ACCOUNTS");
    }

    // ── appliesTo gating ───────────────────────────────────────────

    @Test
    void appliesTo_falseWhenNoConfig() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.empty());
        assertThat(provider.appliesTo(dir)).isFalse();
    }

    @Test
    void appliesTo_falseForInlineMode() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(config(IsvaTopologyMode.INLINE)));
        assertThat(provider.appliesTo(dir)).isFalse();
    }

    @Test
    void appliesTo_trueForLinkedMode() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(config(IsvaTopologyMode.LINKED)));
        assertThat(provider.appliesTo(dir)).isTrue();
    }

    // ── orphan detection ───────────────────────────────────────────

    @Test
    void run_reportsOnlyOrphanedSecUsers() throws Exception {
        // alice: secUser + matching demographic → NOT an orphan.
        String aliceDn = "uid=alice," + PEOPLE_OU;
        inMemoryServer.add(new Entry(aliceDn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "alice"), new Attribute("cn", "Alice"), new Attribute("sn", "A")));
        inMemoryServer.add(new Entry("secUUID=a," + MGMT_BASE,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "a"), new Attribute("secDN", aliceDn),
                new Attribute("secLogin", "alice"), new Attribute("secAcctValid", "TRUE")));
        // ghost: secUser points at a demographic that doesn't exist → orphan.
        inMemoryServer.add(new Entry("secUUID=g," + MGMT_BASE,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "g"), new Attribute("secDN", "uid=ghost," + PEOPLE_OU),
                new Attribute("secLogin", "ghost"), new Attribute("secAcctValid", "TRUE"),
                new Attribute("secValidUntil", "20990101000000Z")));
        // nodn: secUser with no secDN at all → orphan.
        inMemoryServer.add(new Entry("secUUID=n," + MGMT_BASE,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "n"), new Attribute("secLogin", "nodn")));

        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(config(IsvaTopologyMode.LINKED)));

        ReportData data = provider.run(dir, Map.of(), null);

        assertThat(data.columns()).contains(
                "secUser DN", "secLogin", "Demographic DN (secDN)", "Reason");
        // Only the two orphans; alice (linked to a live demographic) is excluded.
        assertThat(data.rows()).hasSize(2);
        assertThat(data.rows().stream().anyMatch(r -> "alice".equals(r.get("secLogin")))).isFalse();

        Map<String, String> ghost = rowWithLogin(data.rows(), "ghost");
        assertThat(ghost.get("Reason")).isEqualTo("Demographic entry not found");
        assertThat(ghost.get("Demographic DN (secDN)")).isEqualToIgnoringCase("uid=ghost," + PEOPLE_OU);
        assertThat(ghost.get("Valid Until")).isEqualTo("20990101000000Z");

        Map<String, String> nodn = rowWithLogin(data.rows(), "nodn");
        assertThat(nodn.get("Reason")).isEqualTo("secDN attribute missing");
        assertThat(nodn.get("Demographic DN (secDN)")).isEmpty();
    }

    @Test
    void run_allLinked_returnsNoRows() throws Exception {
        String bobDn = "uid=bob," + PEOPLE_OU;
        inMemoryServer.add(new Entry(bobDn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "bob"), new Attribute("cn", "Bob"), new Attribute("sn", "B")));
        inMemoryServer.add(new Entry("secUUID=b," + MGMT_BASE,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "b"), new Attribute("secDN", bobDn),
                new Attribute("secLogin", "bob")));
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(config(IsvaTopologyMode.LINKED)));

        ReportData data = provider.run(dir, Map.of(), null);

        assertThat(data.rows()).isEmpty();
    }

    // ── helpers ────────────────────────────────────────────────────

    private static Map<String, String> rowWithLogin(List<Map<String, String>> rows, String login) {
        return rows.stream()
                .filter(r -> login.equals(r.get("secLogin")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No report row for secLogin=" + login));
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
        d.setBaseDn(DEMOGRAPHIC_BASE);
        d.setPoolMinSize(1);
        d.setPoolMaxSize(3);
        d.setPoolConnectTimeoutSeconds(5);
        d.setPoolResponseTimeoutSeconds(10);
        d.setPagingSize(100);
        return d;
    }
}
