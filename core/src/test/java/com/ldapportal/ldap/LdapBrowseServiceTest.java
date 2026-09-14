// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchEntry;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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

    /** Counts every search request the in-memory server receives. */
    private final AtomicInteger searchCount = new AtomicInteger();
    /**
     * Optional per-entry injector for the subordinate hint attributes. The
     * in-memory server doesn't maintain hasSubordinates / numSubordinates
     * itself, so tests that need them stamp the values in on the way out.
     */
    private volatile Function<String, Map<String, String>> hintInjector = dn -> Map.of();

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
        config.addInMemoryOperationInterceptor(new InMemoryOperationInterceptor() {
            @Override
            public void processSearchRequest(InMemoryInterceptedSearchRequest request) {
                searchCount.incrementAndGet();
            }

            @Override
            public void processSearchEntry(InMemoryInterceptedSearchEntry entry) {
                Map<String, String> hints = hintInjector.apply(entry.getSearchEntry().getDN());
                if (hints.isEmpty()) {
                    return;
                }
                Entry patched = entry.getSearchEntry().duplicate();
                hints.forEach((name, value) -> patched.setAttribute(name, value));
                entry.setSearchEntry(patched);
            }
        });
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

    // ── searchPage: truncation, match count, load-all (addPeople is below) ─────

    @Test
    void searchPage_underLimit_isCompleteWithExactTotal() throws Exception {
        addPeople(5); // + Alice = 6 persons

        LdapBrowseService.SearchPage page = browseService.searchPage(
                dc, BASE_DN, SearchScope.SUB, "(objectClass=person)", List.of("cn"), 10, 0, false);

        assertThat(page.entries()).hasSize(6);
        assertThat(page.truncated()).isFalse();
        assertThat(page.total()).isEqualTo(6);
        assertThat(page.totalIsLowerBound()).isFalse();
    }

    @Test
    void searchPage_overLimit_truncatesToLimitAndCountsEveryMatch() throws Exception {
        addPeople(30); // + Alice = 31 persons
        dc.setPagingSize(7); // the count must page through, not stop at one page
        searchCount.set(0);

        LdapBrowseService.SearchPage page = browseService.searchPage(
                dc, BASE_DN, SearchScope.SUB, "(objectClass=person)", List.of("cn"), 10, 0, false);

        assertThat(page.entries()).hasSize(10);
        assertThat(page.truncated()).isTrue();
        assertThat(page.total()).isEqualTo(31);
        assertThat(page.totalIsLowerBound()).isFalse();
    }

    @Test
    void searchPage_limitZero_loadsEveryMatch() throws Exception {
        addPeople(30);
        dc.setPagingSize(7);

        LdapBrowseService.SearchPage page = browseService.searchPage(
                dc, BASE_DN, SearchScope.SUB, "(objectClass=person)", List.of("cn"), 0, 0, false);

        assertThat(page.entries()).hasSize(31);
        assertThat(page.truncated()).isFalse();
        assertThat(page.total()).isEqualTo(31);
    }

    @Test
    void searchPage_noMatches_isEmptyAndComplete() {
        LdapBrowseService.SearchPage page = browseService.searchPage(
                dc, BASE_DN, SearchScope.SUB, "(cn=nobody)", List.of(), 10, 0, false);

        assertThat(page.entries()).isEmpty();
        assertThat(page.truncated()).isFalse();
        assertThat(page.total()).isZero();
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
    void browse_entryView_returnsUserAndOperationalAttributes() {
        // The single-entry browse view requests "*" + "+", so operational
        // attributes (e.g. OUD/OpenDJ's isMemberOf, here entryUUID /
        // createTimestamp from the in-memory server) show up alongside the
        // user attributes — not just the default user set.
        LdapBrowseService.BrowseResult result = browseService.browse(dc, ALICE_DN);

        var attrs = result.attributes();
        assertThat(attrs).containsKeys("cn", "sn", "mail", "objectClass");
        assertThat(attrs).containsKeys("entryUUID", "createTimestamp");
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

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void deleteEntry_childrenOnly_removesDescendants_keepsEntry() throws Exception {
        String ou = "ou=team," + BASE_DN;
        inMemoryServer.add(new Entry(ou,
                new Attribute("objectClass", "top", "organizationalUnit"), new Attribute("ou", "team")));
        inMemoryServer.add(new Entry("cn=Bob," + ou,
                new Attribute("objectClass", "top", "person"),
                new Attribute("cn", "Bob"), new Attribute("sn", "B")));
        String sub = "ou=sub," + ou;
        inMemoryServer.add(new Entry(sub,
                new Attribute("objectClass", "top", "organizationalUnit"), new Attribute("ou", "sub")));
        inMemoryServer.add(new Entry("cn=Carl," + sub,
                new Attribute("objectClass", "top", "person"),
                new Attribute("cn", "Carl"), new Attribute("sn", "C")));

        browseService.deleteEntry(dc, ou, false, true);

        assertThat(inMemoryServer.getEntry(ou)).isNotNull();            // entry kept
        assertThat(inMemoryServer.getEntry("cn=Bob," + ou)).isNull();   // direct child gone
        assertThat(inMemoryServer.getEntry(sub)).isNull();              // nested subtree gone
        assertThat(inMemoryServer.getEntry("cn=Carl," + sub)).isNull();
    }

    @Test
    void deleteEntry_default_removesTheEntryItself() throws Exception {
        browseService.deleteEntry(dc, ALICE_DN, false, false);
        assertThat(inMemoryServer.getEntry(ALICE_DN)).isNull();
    }

    // ── child listing: subordinate hints vs. per-child probe ─────────────────

    private static final String TEAM_OU = "ou=team," + BASE_DN;

    /** Adds ou=team (with one child) so the base has one container and one leaf. */
    private void addTeamBranch() throws Exception {
        inMemoryServer.add(new Entry(TEAM_OU,
                new Attribute("objectClass", "top", "organizationalUnit"), new Attribute("ou", "team")));
        inMemoryServer.add(new Entry("cn=Bob," + TEAM_OU,
                new Attribute("objectClass", "top", "person"),
                new Attribute("cn", "Bob"), new Attribute("sn", "B")));
    }

    private LdapBrowseService.ChildEntry child(LdapBrowseService.BrowseResult result, String dn) {
        return result.children().stream()
                .filter(c -> c.dn().equalsIgnoreCase(dn))
                .findFirst()
                .orElseThrow(() -> new AssertionError("child not listed: " + dn));
    }

    @Test
    void browse_noSubordinateHints_probesEachChild() throws Exception {
        addTeamBranch();
        searchCount.set(0);

        LdapBrowseService.BrowseResult result = browseService.browse(dc, BASE_DN);

        assertThat(child(result, TEAM_OU).hasChildren()).isTrue();
        assertThat(child(result, ALICE_DN).hasChildren()).isFalse();
        // 1 entry read + 1 one-level listing + 1 probe per child (2 children).
        assertThat(searchCount.get()).isEqualTo(4);
    }

    @Test
    void browse_hasSubordinatesPresent_skipsPerChildProbe() throws Exception {
        addTeamBranch();
        hintInjector = dn -> dn.equalsIgnoreCase(TEAM_OU)
                ? Map.of("hasSubordinates", "TRUE")
                : Map.of("hasSubordinates", "FALSE");
        searchCount.set(0);

        LdapBrowseService.BrowseResult result = browseService.browse(dc, BASE_DN);

        assertThat(child(result, TEAM_OU).hasChildren()).isTrue();
        assertThat(child(result, ALICE_DN).hasChildren()).isFalse();
        // 1 entry read + 1 one-level listing — no probes at all.
        assertThat(searchCount.get()).isEqualTo(2);
    }

    @Test
    void browse_numSubordinatesPresent_skipsPerChildProbe() throws Exception {
        addTeamBranch();
        hintInjector = dn -> dn.equalsIgnoreCase(TEAM_OU)
                ? Map.of("numSubordinates", "1")
                : Map.of("numSubordinates", "0");
        searchCount.set(0);

        LdapBrowseService.BrowseResult result = browseService.browse(dc, BASE_DN);

        assertThat(child(result, TEAM_OU).hasChildren()).isTrue();
        assertThat(child(result, ALICE_DN).hasChildren()).isFalse();
        assertThat(searchCount.get()).isEqualTo(2);
    }

    @Test
    void browse_hintOnSomeChildrenOnly_probesOnlyTheOthers() throws Exception {
        addTeamBranch();
        // Only the container carries a hint; the leaf must still be probed.
        hintInjector = dn -> dn.equalsIgnoreCase(TEAM_OU)
                ? Map.of("hasSubordinates", "TRUE")
                : Map.of();
        searchCount.set(0);

        LdapBrowseService.BrowseResult result = browseService.browse(dc, BASE_DN);

        assertThat(child(result, TEAM_OU).hasChildren()).isTrue();
        assertThat(child(result, ALICE_DN).hasChildren()).isFalse();
        // entry read + listing + exactly one probe (for Alice).
        assertThat(searchCount.get()).isEqualTo(3);
    }

    // ── child page: limit, filter, count ──────────────────────────────────────

    /** Adds {@code n} leaf people directly under the base, cn=p00 … cn=p(n-1). */
    private void addPeople(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            String cn = String.format("p%02d", i);
            inMemoryServer.add(new Entry("cn=" + cn + "," + BASE_DN,
                    new Attribute("objectClass", "top", "person"),
                    new Attribute("cn", cn), new Attribute("sn", "P")));
        }
    }

    @Test
    void browse_limit_returnsFirstPageAndFlagsTruncation() throws Exception {
        addPeople(12);                       // + Alice = 13 children
        // Leaves carry an explicit "no children" hint so the probe count
        // doesn't blur the assertion below.
        hintInjector = dn -> Map.of("hasSubordinates", "FALSE");
        searchCount.set(0);

        LdapBrowseService.BrowseResult result = browseService.browse(dc, BASE_DN, null, 5);

        assertThat(result.children()).hasSize(5);
        assertThat(result.truncated()).isTrue();
        // No server-side count and the listing was cut short → unknown.
        assertThat(result.childCount()).isNull();
        // entry read + one listing page (limit + 1 fits in one page) + the
        // zero-size request that releases the server's paging state.
        assertThat(searchCount.get()).isEqualTo(3);
    }

    @Test
    void browse_limitZero_returnsEverythingWithExactCount() throws Exception {
        addPeople(12);

        LdapBrowseService.BrowseResult result = browseService.browse(dc, BASE_DN, null, 0);

        assertThat(result.children()).hasSize(13);
        assertThat(result.truncated()).isFalse();
        assertThat(result.childCount()).isEqualTo(13);
        assertThat(result.childCountApproximate()).isFalse();
    }

    @Test
    void browse_limitAcrossPages_stopsAtLimit() throws Exception {
        addPeople(12);
        dc.setPagingSize(4);                 // force several pages

        LdapBrowseService.BrowseResult result = browseService.browse(dc, BASE_DN, null, 10);

        assertThat(result.children()).hasSize(10);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void browse_quickFilter_matchesSubstringAcrossNamingAttributes() throws Exception {
        addTeamBranch();                     // ou=team + cn=Alice under base

        LdapBrowseService.BrowseResult byOu = browseService.browse(dc, BASE_DN, "tea", 0);
        assertThat(byOu.children()).extracting(LdapBrowseService.ChildEntry::rdn)
                .containsExactly("ou=team");

        LdapBrowseService.BrowseResult byCn = browseService.browse(dc, BASE_DN, "LIC", 0);
        assertThat(byCn.children()).extracting(LdapBrowseService.ChildEntry::rdn)
                .containsExactly("cn=Alice");

        // A filtered listing says nothing about the branch's total.
        assertThat(byCn.childCount()).isNull();
        assertThat(byCn.truncated()).isFalse();
    }

    @Test
    void browse_quickFilter_treatsFilterMetacharactersLiterally() throws Exception {
        addTeamBranch();
        // "*" would match everything if it were passed through unescaped.
        LdapBrowseService.BrowseResult result = browseService.browse(dc, BASE_DN, "*", 0);
        assertThat(result.children()).isEmpty();
    }

    @Test
    void browse_rawFilter_isUsedVerbatim() throws Exception {
        addTeamBranch();

        LdapBrowseService.BrowseResult result = browseService.browse(dc, BASE_DN, "(cn=Al*)", 0);

        assertThat(result.children()).extracting(LdapBrowseService.ChildEntry::rdn)
                .containsExactly("cn=Alice");
    }

    @Test
    void browse_serverCountOnParent_isReportedWhenTruncated() throws Exception {
        addPeople(12);
        hintInjector = dn -> dn.equalsIgnoreCase(BASE_DN)
                ? Map.of("numSubordinates", "13")
                : Map.of();

        LdapBrowseService.BrowseResult result = browseService.browse(dc, BASE_DN, null, 5);

        assertThat(result.truncated()).isTrue();
        assertThat(result.childCount()).isEqualTo(13);
        assertThat(result.childCountApproximate()).isFalse();
    }

    @Test
    void browse_adEstimate_isFlaggedApproximate_untilAFullListingBeatsIt() throws Exception {
        addPeople(12);
        hintInjector = dn -> dn.equalsIgnoreCase(BASE_DN)
                ? Map.of("msDS-Approx-Immed-Subordinates", "99")
                : Map.of();

        LdapBrowseService.BrowseResult page = browseService.browse(dc, BASE_DN, null, 5);
        assertThat(page.childCount()).isEqualTo(99);
        assertThat(page.childCountApproximate()).isTrue();

        LdapBrowseService.BrowseResult all = browseService.browse(dc, BASE_DN, null, 0);
        assertThat(all.childCount()).isEqualTo(13);
        assertThat(all.childCountApproximate()).isFalse();
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
