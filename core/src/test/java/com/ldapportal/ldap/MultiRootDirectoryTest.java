// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.dto.ldap.IntegrityReport;
import com.ldapportal.dto.ldap.IntegrityReport.IssueType;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.model.LdapUser;
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * A directory with two top-level roots (suffixes). A search with no explicit
 * base must cover both; an explicit base still searches only under itself; a
 * single-root connection behaves exactly as before.
 */
@ExtendWith(MockitoExtension.class)
class MultiRootDirectoryTest {

    private static final String CORP = "dc=example,dc=com";
    private static final String PARTNERS = "o=partners";
    private static final String BIND_DN = "cn=admin,dc=example,dc=com";
    private static final String BIND_PASS = "adminpass";

    @Mock private EncryptionService encryptionService;

    private InMemoryDirectoryServer server;
    private LdapConnectionFactory connectionFactory;
    private LdapUserService userService;
    private LdapGroupService groupService;
    private LdapBrowseService browseService;
    private IntegrityCheckService integrityService;
    private DirectoryConnection dc;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(CORP, PARTNERS);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        config.setSchema(null);
        server = new InMemoryDirectoryServer(config);
        server.add(new Entry(CORP, new Attribute("objectClass", "top", "domain"), new Attribute("dc", "example")));
        server.add(new Entry(PARTNERS, new Attribute("objectClass", "top", "organization"),
                new Attribute("o", "partners")));
        person("alice", CORP);
        person("bob", CORP);
        person("carol", PARTNERS);
        // A corp group whose members live in both roots.
        server.add(new Entry("cn=staff," + CORP, new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", "staff"),
                new Attribute("member", "uid=alice," + CORP, "uid=carol," + PARTNERS)));
        server.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService);
        userService = new LdapUserService(connectionFactory,
                new com.ldapportal.core.provisioning.ProvisioningInterceptorChain(List.of()),
                new com.ldapportal.core.provisioning.PlanExecutor(connectionFactory),
                new com.ldapportal.core.provisioning.UserReadEnricherChain(List.of()));
        groupService = new LdapGroupService(connectionFactory,
                new com.ldapportal.core.provisioning.ProvisioningInterceptorChain(List.of()),
                new com.ldapportal.core.provisioning.PlanExecutor(connectionFactory));
        browseService = new LdapBrowseService(connectionFactory);
        integrityService = new IntegrityCheckService(connectionFactory);

        dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setDisplayName("multi-root");
        dc.setHost("localhost");
        dc.setPort(server.getListenPort());
        dc.setSslMode(SslMode.NONE);
        dc.setBindDn(BIND_DN);
        dc.setBindPasswordEncrypted("enc-placeholder");
        dc.setBaseDn(CORP);
        dc.setAdditionalBaseDns(new ArrayList<>(List.of(PARTNERS)));
        dc.setPoolMinSize(1);
        dc.setPoolMaxSize(3);
        dc.setPoolConnectTimeoutSeconds(5);
        dc.setPoolResponseTimeoutSeconds(10);
        dc.setPagingSize(100);
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        server.shutDown(true);
    }

    private void person(String uid, String root) throws Exception {
        server.add(new Entry("uid=" + uid + "," + root,
                new Attribute("objectClass", "top", "person", "inetOrgPerson"),
                new Attribute("uid", uid), new Attribute("cn", uid), new Attribute("sn", uid)));
    }

    private static List<String> dns(List<LdapUser> users) {
        return users.stream().map(u -> u.getDn().toLowerCase()).toList();
    }

    @Test
    void searchWithoutBase_coversEveryRoot_primaryFirst() {
        List<LdapUser> users = userService.searchUsers(dc, "(objectClass=inetOrgPerson)", null, 10);

        assertThat(dns(users)).containsExactly(
                "uid=alice,dc=example,dc=com", "uid=bob,dc=example,dc=com", "uid=carol,o=partners");
    }

    @Test
    void searchWithoutBase_stopsAtTheLimitAcrossRoots() {
        assertThat(userService.searchUsers(dc, "(objectClass=inetOrgPerson)", null, 2)).hasSize(2);
        assertThat(userService.searchUsers(dc, "(objectClass=inetOrgPerson)", null, 3)).hasSize(3);
    }

    @Test
    void explicitBase_searchesOnlyUnderIt() {
        assertThat(dns(userService.searchUsers(dc, "(objectClass=inetOrgPerson)", PARTNERS, 10)))
                .containsExactly("uid=carol,o=partners");
    }

    @Test
    void singleRootConnection_isUnchanged() {
        dc.setAdditionalBaseDns(new ArrayList<>());

        assertThat(dns(userService.searchUsers(dc, "(objectClass=inetOrgPerson)", null, 10)))
                .containsExactly("uid=alice,dc=example,dc=com", "uid=bob,dc=example,dc=com");
    }

    @Test
    void countsAndStreams_coverEveryRoot() {
        assertThat(userService.countUsers(dc, "(objectClass=inetOrgPerson)", null, 100)).isEqualTo(3);
        assertThat(userService.countUsers(dc, "(objectClass=inetOrgPerson)", null, 2)).isEqualTo(2);

        List<String> streamed = new ArrayList<>();
        userService.processUsers(dc, "(objectClass=inetOrgPerson)", null, u -> streamed.add(u.getDn()));
        assertThat(streamed).hasSize(3);
    }

    @Test
    void groupSearchWithoutBase_coversEveryRoot() throws Exception {
        server.add(new Entry("cn=vendors," + PARTNERS, new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", "vendors"), new Attribute("member", "uid=carol," + PARTNERS)));

        List<LdapGroup> groups = groupService.searchGroups(dc, "(objectClass=groupOfNames)", null, 10);

        assertThat(groups).extracting(g -> g.getDn().toLowerCase())
                .containsExactly("cn=staff,dc=example,dc=com", "cn=vendors,o=partners");
    }

    @Test
    void directorySearchPage_withoutBase_coversEveryRoot() {
        LdapBrowseService.SearchPage page = browseService.searchPage(dc, null, SearchScope.SUB,
                "(objectClass=inetOrgPerson)", List.of("uid"), 2, 0, false);

        assertThat(page.entries()).hasSize(2);
        assertThat(page.truncated()).isTrue();
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void browseOfTheDirectoryRoot_listsTheOtherRoots() {
        assertThat(browseService.browse(dc, null).dn()).isEqualTo(CORP);
        assertThat(browseService.browse(dc, null).otherRoots()).containsExactly(PARTNERS);
        // Browsing a specific entry (a root included) lists none.
        assertThat(browseService.browse(dc, PARTNERS).otherRoots()).isEmpty();

        dc.setAdditionalBaseDns(new ArrayList<>());
        assertThat(browseService.browse(dc, null).otherRoots()).isEmpty();
    }

    @Test
    void integrityCheck_acrossRoots_acceptsMembersInAnotherRoot_andExemptsEachRoot() {
        IntegrityReport report = integrityService.runChecks(dc, null,
                EnumSet.of(IssueType.BROKEN_MEMBER, IssueType.ORPHANED_ENTRY));

        // carol is under o=partners: present, so the staff group isn't broken;
        // neither root is reported as an orphan.
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void integrityCheck_stillFlagsATrulyBrokenMember() throws Exception {
        server.modify("cn=staff," + CORP, new com.unboundid.ldap.sdk.Modification(
                com.unboundid.ldap.sdk.ModificationType.ADD, "member", "uid=ghost,o=partners"));

        IntegrityReport report = integrityService.runChecks(dc, null, EnumSet.of(IssueType.BROKEN_MEMBER));

        assertThat(report.issues()).singleElement()
                .satisfies(i -> assertThat(i.description()).containsIgnoringCase("uid=ghost,o=partners"));
    }
}
