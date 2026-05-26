// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.SearchScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LdapBrowseService} focusing on the attribute-array
 * construction in {@link LdapBrowseService#searchEntries} — the four
 * quadrants of (attributes empty / nonempty) × (includeOperational true /
 * false). These behaviours are easy to regress and the smoke spec only
 * exercises one of the four combinations.
 *
 * <p>Uses an UnboundID {@link InMemoryDirectoryServer} so the service's
 * actual LDAP request shape is exercised. The InMemoryDirectoryServer
 * automatically populates standard operational attributes (entryUUID,
 * createTimestamp, modifyTimestamp) on every entry, mirroring the
 * behaviour of OpenLDAP / 389DS / AD.
 */
@ExtendWith(MockitoExtension.class)
class LdapBrowseServiceTest {

    @Mock private EncryptionService encryptionService;

    private LdapConnectionFactory   connectionFactory;
    private LdapBrowseService       browseService;
    private InMemoryDirectoryServer inMemoryServer;
    private DirectoryConnection     dc;

    private static final String BASE_DN   = "dc=example,dc=com";
    private static final String BIND_DN   = "cn=admin,dc=example,dc=com";
    private static final String BIND_PASS = "adminpass";
    private static final String ALICE_DN  = "cn=Alice,dc=example,dc=com";

    @BeforeEach
    void setUp() throws Exception {
        // Default schema (no setSchema call) so the in-memory server
        // recognises operational-attribute definitions and auto-populates
        // entryUUID / createTimestamp / modifyTimestamp on every entry.
        // With schema=null, operational attrs aren't generated and the
        // includeOperational tests can't verify their presence.
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        inMemoryServer = new InMemoryDirectoryServer(config);

        inMemoryServer.add(new Entry(BASE_DN,
                new Attribute("objectClass", "top", "domain"),
                new Attribute("dc", "example")));
        // cn=Alice — give it under the base DN. The default schema accepts
        // inetOrgPerson with cn + sn as MUST attributes.
        inMemoryServer.add(new Entry(ALICE_DN,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("cn", "Alice"),
                new Attribute("sn", "Smith"),
                new Attribute("mail", "alice@example.com")));

        inMemoryServer.startListening();

        when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService);
        browseService = new LdapBrowseService(connectionFactory);
        dc = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        inMemoryServer.shutDown(true);
    }

    // ── attribute-array construction (the 4 quadrants) ───────────────────────

    @Test
    void search_emptyAttrs_noOperational_returnsAllUserAttrs() {
        List<LdapBrowseService.SearchEntry> results = browseService.searchEntries(
                dc, BASE_DN, SearchScope.SUB, "(cn=Alice)",
                List.of(), 100, 0, false);

        assertThat(results).hasSize(1);
        var attrs = results.get(0).attributes();
        assertThat(attrs).containsKeys("cn", "sn", "mail", "objectClass");
        // Operational attrs are NOT requested, so they should NOT appear.
        // The InMemoryDirectoryServer populates entryUUID + createTimestamp
        // on every entry but only returns them when explicitly requested.
        assertThat(attrs).doesNotContainKey("entryUUID");
        assertThat(attrs).doesNotContainKey("createTimestamp");
    }

    @Test
    void search_emptyAttrs_withOperational_returnsUserAndOperationalAttrs() {
        List<LdapBrowseService.SearchEntry> results = browseService.searchEntries(
                dc, BASE_DN, SearchScope.SUB, "(cn=Alice)",
                List.of(), 100, 0, true);

        assertThat(results).hasSize(1);
        var attrs = results.get(0).attributes();
        // User attributes still come back — this is the * + + behaviour
        // (without the *, only operational attrs would return).
        assertThat(attrs).containsKeys("cn", "sn", "mail", "objectClass");
        // Plus operational attrs that the in-memory server provides.
        assertThat(attrs).containsKey("entryUUID");
    }

    @Test
    void search_nonEmptyAttrs_noOperational_returnsOnlyRequestedAttrs() {
        List<LdapBrowseService.SearchEntry> results = browseService.searchEntries(
                dc, BASE_DN, SearchScope.SUB, "(cn=Alice)",
                List.of("cn", "mail"), 100, 0, false);

        assertThat(results).hasSize(1);
        var attrs = results.get(0).attributes();
        assertThat(attrs).containsOnlyKeys("cn", "mail");
        assertThat(attrs).doesNotContainKey("sn");
        assertThat(attrs).doesNotContainKey("entryUUID");
    }

    @Test
    void search_nonEmptyAttrs_withOperational_appendsOperationalToRequested() {
        List<LdapBrowseService.SearchEntry> results = browseService.searchEntries(
                dc, BASE_DN, SearchScope.SUB, "(cn=Alice)",
                List.of("cn"), 100, 0, true);

        assertThat(results).hasSize(1);
        var attrs = results.get(0).attributes();
        // The explicit attribute (cn) comes back...
        assertThat(attrs).containsKey("cn");
        // ...as do operational attrs because of the appended "+".
        assertThat(attrs).containsKey("entryUUID");
        // But sn was NOT requested and is NOT operational, so it should
        // not be here. This is the key difference from the empty-attrs
        // case: explicit user-attr requests don't get implicitly
        // augmented with "*".
        assertThat(attrs).doesNotContainKey("sn");
    }

    // ── backward-compat overload ─────────────────────────────────────────────

    @Test
    void search_legacyOverload_delegatesWithDefaults() {
        // The 6-arg overload should behave identically to the 8-arg
        // overload called with timeLimit=0, includeOperational=false.
        List<LdapBrowseService.SearchEntry> viaLegacy = browseService.searchEntries(
                dc, BASE_DN, SearchScope.SUB, "(cn=Alice)",
                List.of(), 100);
        List<LdapBrowseService.SearchEntry> viaFull = browseService.searchEntries(
                dc, BASE_DN, SearchScope.SUB, "(cn=Alice)",
                List.of(), 100, 0, false);

        assertThat(viaLegacy).hasSize(1);
        assertThat(viaFull).hasSize(1);
        assertThat(viaLegacy.get(0).attributes().keySet())
                .isEqualTo(viaFull.get(0).attributes().keySet());
    }

    // ── time limit threading ─────────────────────────────────────────────────

    @Test
    void search_zeroTimeLimit_succeeds() {
        // Ensures the timeLimitSeconds == 0 path doesn't accidentally
        // call setTimeLimitSeconds(0), which UnboundID treats as "no
        // limit" but is more explicit when never invoked. Successful
        // search with a normal result is the contract.
        List<LdapBrowseService.SearchEntry> results = browseService.searchEntries(
                dc, BASE_DN, SearchScope.SUB, "(cn=Alice)",
                List.of("cn"), 100, 0, false);

        assertThat(results).hasSize(1);
    }

    @Test
    void search_positiveTimeLimit_succeeds() {
        // 60s is well above the in-memory server's response time so
        // the timeout doesn't fire; we're just verifying the
        // setTimeLimitSeconds() call doesn't break the search request.
        List<LdapBrowseService.SearchEntry> results = browseService.searchEntries(
                dc, BASE_DN, SearchScope.SUB, "(cn=Alice)",
                List.of("cn"), 100, 60, false);

        assertThat(results).hasSize(1);
    }

    // ── entry existence + container creation ─────────────────────────────────

    @Test
    void entryExists_presentDn_returnsTrue() {
        assertThat(browseService.entryExists(dc, ALICE_DN)).isTrue();
    }

    @Test
    void entryExists_missingDn_returnsFalse() {
        assertThat(browseService.entryExists(dc, "ou=missing,dc=example,dc=com")).isFalse();
        // Blank/null short-circuit doesn't open a connection — also asserted
        // here so the strict-stubbing check is happy without a separate test.
        assertThat(browseService.entryExists(dc, "")).isFalse();
        assertThat(browseService.entryExists(dc, null)).isFalse();
    }

    @Test
    void createContainer_ouRdn_addsOrganizationalUnit() throws Exception {
        String dn = "ou=people,dc=example,dc=com";
        assertThat(browseService.entryExists(dc, dn)).isFalse();

        browseService.createContainer(dc, dn);

        assertThat(browseService.entryExists(dc, dn)).isTrue();
        var entry = inMemoryServer.getEntry(dn);
        assertThat(entry.getAttributeValues("objectClass"))
                .contains("organizationalUnit");
        assertThat(entry.getAttributeValue("ou")).isEqualTo("people");
    }

    @Test
    void createContainer_oRdn_addsOrganization() throws Exception {
        String dn = "o=Acme,dc=example,dc=com";

        browseService.createContainer(dc, dn);

        assertThat(browseService.entryExists(dc, dn)).isTrue();
        assertThat(inMemoryServer.getEntry(dn).getAttributeValues("objectClass"))
                .contains("organization");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private DirectoryConnection buildDc() {
        DirectoryConnection d = new DirectoryConnection();
        d.setId(UUID.randomUUID());
        d.setDisplayName("test-ldap");
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
