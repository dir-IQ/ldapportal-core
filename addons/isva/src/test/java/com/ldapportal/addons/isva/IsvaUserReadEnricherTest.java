// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.model.LdapUser;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * End-to-end enricher tests against an in-memory UnboundID
 * directory carrying both a demographic OU and a management DIT
 * populated with secUser entries linked via {@code secDN}.
 */
@ExtendWith(MockitoExtension.class)
class IsvaUserReadEnricherTest {

    @Mock private EncryptionService encryptionService;
    @Mock private VendorIntegrationIsvaConfigRepository configRepo;

    private InMemoryDirectoryServer inMemoryServer;
    private LdapConnectionFactory connectionFactory;
    private IsvaUserReadEnricher enricher;
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
        // Seed both DITs: demographic side + management side.
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
        connectionFactory = new LdapConnectionFactory(encryptionService, null);
        enricher = new IsvaUserReadEnricher(configRepo, connectionFactory);
        dir = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        inMemoryServer.shutDown(true);
    }

    // ── no-op cases ────────────────────────────────────────────────

    @Test
    void noConfigRow_passesThrough_unchanged() {
        when(configRepo.findById(dir.getId())).thenReturn(Optional.empty());

        LdapUser alice = new LdapUser("uid=alice," + PEOPLE_OU,
                attrs("uid", "alice", "cn", "Alice"));

        List<LdapUser> got = enricher.enrichBatch(dir, List.of(alice));

        assertThat(got).containsExactly(alice);  // same instance — no augmentation
    }

    @Test
    void inlineMode_passesThrough_unchanged() {
        // Inline-mode directories already carry sec* on the user
        // entry — no joined-read needed; enricher is a no-op.
        VendorIntegrationIsvaConfig cfg = enabledConfig(IsvaTopologyMode.INLINE);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));

        LdapUser alice = new LdapUser("uid=alice," + PEOPLE_OU,
                attrs("uid", "alice"));

        List<LdapUser> got = enricher.enrichBatch(dir, List.of(alice));

        assertThat(got).containsExactly(alice);
    }

    @Test
    void emptyBatch_returnsEmpty_noLdapHit() {
        // Empty input short-circuits before any config / LDAP work —
        // no stub needed (Mockito would flag an unused configRepo
        // stub here). The assertion is "no exception, empty output";
        // a query against an empty OR filter would have thrown.
        assertThat(enricher.enrichBatch(dir, List.of())).isEmpty();
    }

    // ── linked-mode joined-read ────────────────────────────────────

    @Test
    void linkedMode_singleUser_mergesSecAttributes() throws Exception {
        String demographicDn = "uid=alice," + PEOPLE_OU;
        String secUserDn = "secUUID=abc," + MGMT_BASE;
        inMemoryServer.add(new Entry(demographicDn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "alice"), new Attribute("cn", "Alice"), new Attribute("sn", "A")));
        inMemoryServer.add(new Entry(secUserDn,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "abc"),
                new Attribute("secDN", demographicDn),
                new Attribute("secLogin", "alice"),
                new Attribute("secAcctValid", "TRUE"),
                new Attribute("secAuthority", "Default")));
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(
                enabledConfig(IsvaTopologyMode.LINKED)));

        LdapUser alice = new LdapUser(demographicDn,
                attrs("uid", "alice", "cn", "Alice"));

        List<LdapUser> got = enricher.enrichBatch(dir, List.of(alice));

        assertThat(got).hasSize(1);
        LdapUser enriched = got.get(0);
        // Demographic attrs preserved.
        assertThat(enriched.getFirstValue("uid")).isEqualTo("alice");
        assertThat(enriched.getFirstValue("cn")).isEqualTo("Alice");
        // sec* attrs surfaced with isva.* prefix (lowercased).
        assertThat(enriched.getFirstValue("isva.seclogin")).isEqualTo("alice");
        assertThat(enriched.getFirstValue("isva.secacctvalid")).isEqualTo("TRUE");
        assertThat(enriched.getFirstValue("isva.secauthority")).isEqualTo("Default");
        // Orphan flag set to false (not orphaned).
        assertThat(enriched.getFirstValue("isva.orphaned")).isEqualTo("false");
        // secUserDn surfaced for the UI's joined-view affordances.
        assertThat(enriched.getFirstValue("isva.secuserdn"))
                .isEqualToIgnoringCase(secUserDn);
    }

    @Test
    void linkedMode_orphanedDemographic_marksOrphanedTrue() throws Exception {
        // Demographic entry exists, no paired secUser. Enricher
        // tags it instead of dropping the row.
        String demographicDn = "uid=orphan," + PEOPLE_OU;
        inMemoryServer.add(new Entry(demographicDn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "orphan"), new Attribute("cn", "Orphan"), new Attribute("sn", "O")));
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(
                enabledConfig(IsvaTopologyMode.LINKED)));

        LdapUser orphan = new LdapUser(demographicDn,
                attrs("uid", "orphan", "cn", "Orphan"));

        List<LdapUser> got = enricher.enrichBatch(dir, List.of(orphan));

        assertThat(got.get(0).getFirstValue("isva.orphaned")).isEqualTo("true");
        // Demographic attrs still present.
        assertThat(got.get(0).getFirstValue("uid")).isEqualTo("orphan");
        // No sec* attrs surfaced — there were none to surface.
        assertThat(got.get(0).getFirstValue("isva.seclogin")).isNull();
    }

    @Test
    void linkedMode_batchedLookup_oneSearchHandlesMultipleUsers() throws Exception {
        // The key optimisation: ONE search per page covers all
        // users in the batch. Set up three demographics + matching
        // secUsers, then run enrichBatch on all three together.
        String aliceDn = "uid=alice," + PEOPLE_OU;
        String bobDn   = "uid=bob,"   + PEOPLE_OU;
        String carolDn = "uid=carol," + PEOPLE_OU;
        inMemoryServer.add(new Entry(aliceDn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "alice"), new Attribute("cn", "Alice"), new Attribute("sn", "A")));
        inMemoryServer.add(new Entry(bobDn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "bob"), new Attribute("cn", "Bob"), new Attribute("sn", "B")));
        inMemoryServer.add(new Entry(carolDn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "carol"), new Attribute("cn", "Carol"), new Attribute("sn", "C")));
        // Pair alice + carol; bob is intentionally orphaned to
        // cover the "mixed batch" path.
        inMemoryServer.add(new Entry("secUUID=a," + MGMT_BASE,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "a"), new Attribute("secDN", aliceDn),
                new Attribute("secLogin", "alice")));
        inMemoryServer.add(new Entry("secUUID=c," + MGMT_BASE,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "c"), new Attribute("secDN", carolDn),
                new Attribute("secLogin", "carol")));
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(
                enabledConfig(IsvaTopologyMode.LINKED)));

        List<LdapUser> got = enricher.enrichBatch(dir, List.of(
                new LdapUser(aliceDn, attrs("uid", "alice")),
                new LdapUser(bobDn,   attrs("uid", "bob")),
                new LdapUser(carolDn, attrs("uid", "carol"))));

        // Order preserved.
        assertThat(got).extracting(LdapUser::getDn)
                .containsExactly(aliceDn, bobDn, carolDn);
        // alice + carol joined; bob marked orphaned.
        assertThat(got.get(0).getFirstValue("isva.orphaned")).isEqualTo("false");
        assertThat(got.get(0).getFirstValue("isva.seclogin")).isEqualTo("alice");
        assertThat(got.get(1).getFirstValue("isva.orphaned")).isEqualTo("true");
        assertThat(got.get(2).getFirstValue("isva.orphaned")).isEqualTo("false");
        assertThat(got.get(2).getFirstValue("isva.seclogin")).isEqualTo("carol");
    }

    @Test
    void linkedMode_dnCaseInsensitive_join() throws Exception {
        // LDAP DNs are case-insensitive by spec; the enricher must
        // match across case differences. Seed the secDN value in
        // a different case than what LdapUserService returned for
        // the demographic DN.
        String demographicDn = "uid=Mixed," + PEOPLE_OU;
        inMemoryServer.add(new Entry(demographicDn,
                new Attribute("objectClass", "inetOrgPerson"),
                new Attribute("uid", "Mixed"), new Attribute("cn", "Mixed"), new Attribute("sn", "M")));
        inMemoryServer.add(new Entry("secUUID=m," + MGMT_BASE,
                new Attribute("objectClass", "top", "secUser"),
                new Attribute("secUUID", "m"),
                new Attribute("secDN", "UID=mixed,OU=PEOPLE,DC=EXAMPLE,DC=COM"),
                new Attribute("secLogin", "mixed")));
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(
                enabledConfig(IsvaTopologyMode.LINKED)));

        List<LdapUser> got = enricher.enrichBatch(dir, List.of(
                new LdapUser(demographicDn, attrs("uid", "Mixed"))));

        assertThat(got.get(0).getFirstValue("isva.orphaned")).isEqualTo("false");
        assertThat(got.get(0).getFirstValue("isva.seclogin")).isEqualTo("mixed");
    }

    @Test
    void linkedMode_missingManagementDit_skipsEnrichment() {
        // Defensive: a misconfigured config row with NULL
        // management_dit_base_dn shouldn't crash the read path. The
        // DB CHECK constraint should prevent this, but enricher
        // tolerance is cheap.
        VendorIntegrationIsvaConfig cfg = enabledConfig(IsvaTopologyMode.LINKED);
        cfg.setManagementDitBaseDn(null);
        when(configRepo.findById(dir.getId())).thenReturn(Optional.of(cfg));

        LdapUser alice = new LdapUser("uid=alice," + PEOPLE_OU,
                attrs("uid", "alice"));

        List<LdapUser> got = enricher.enrichBatch(dir, List.of(alice));

        // Original list (no augmentation, no orphan flag).
        assertThat(got).containsExactly(alice);
    }

    // ── helpers ────────────────────────────────────────────────────

    private VendorIntegrationIsvaConfig enabledConfig(IsvaTopologyMode mode) {
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

    private static Map<String, List<String>> attrs(String... kv) {
        Map<String, List<String>> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], List.of(kv[i + 1]));
        }
        return m;
    }
}
