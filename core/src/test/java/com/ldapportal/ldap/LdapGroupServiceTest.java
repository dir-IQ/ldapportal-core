// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.model.LdapGroup;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LdapGroupService} using an in-memory LDAP server.
 */
@ExtendWith(MockitoExtension.class)
class LdapGroupServiceTest {

    @Mock private EncryptionService encryptionService;

    private LdapConnectionFactory connectionFactory;
    private LdapGroupService       groupService;
    private InMemoryDirectoryServer inMemoryServer;
    private DirectoryConnection     dc;

    private static final String BASE_DN    = "dc=example,dc=com";
    private static final String GROUPS_OU  = "ou=groups,dc=example,dc=com";
    private static final String USERS_OU   = "ou=users,dc=example,dc=com";
    private static final String BIND_DN    = "cn=admin,dc=example,dc=com";
    private static final String BIND_PASS  = "adminpass";

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig(BASE_DN);
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        config.setSchema(null);
        inMemoryServer = new InMemoryDirectoryServer(config);

        inMemoryServer.add(new Entry(BASE_DN,
                new Attribute("objectClass", "top", "domain"),
                new Attribute("dc", "example")));
        inMemoryServer.add(new Entry(GROUPS_OU,
                new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "groups")));
        inMemoryServer.add(new Entry(USERS_OU,
                new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "users")));

        inMemoryServer.startListening();

        when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService, null);
        // Empty interceptor chain — see LdapUserServiceTest setUp.
        groupService = new LdapGroupService(
                connectionFactory,
                new com.ldapportal.core.provisioning.ProvisioningInterceptorChain(java.util.List.of()),
                new com.ldapportal.core.provisioning.PlanExecutor(connectionFactory));
        dc = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        inMemoryServer.shutDown(true);
    }

    // ── searchGroups ──────────────────────────────────────────────────────────

    @Test
    void searchGroups_noGroups_returnsEmpty() {
        List<LdapGroup> result = groupService.searchGroups(dc, "(objectClass=groupOfNames)", null);
        assertThat(result).isEmpty();
    }

    @Test
    void searchGroups_withGroup_returnsMatch() throws Exception {
        addGroup("cn=Staff,ou=groups,dc=example,dc=com", "Staff",
                "cn=Alice,ou=users,dc=example,dc=com");

        List<LdapGroup> result = groupService.searchGroups(dc, "(cn=Staff)", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDn()).isEqualTo("cn=Staff,ou=groups,dc=example,dc=com");
    }

    @Test
    void searchGroups_filterNarrowsResults() throws Exception {
        addGroup("cn=Staff,ou=groups,dc=example,dc=com",  "Staff",  "cn=A,ou=users,dc=example,dc=com");
        addGroup("cn=Admins,ou=groups,dc=example,dc=com", "Admins", "cn=B,ou=users,dc=example,dc=com");

        List<LdapGroup> result = groupService.searchGroups(dc, "(cn=Admins)", null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDn()).contains("Admins");
    }

    @Test
    void searchGroups_customBaseDn_usedAsRoot() throws Exception {
        addGroup("cn=Staff,ou=groups,dc=example,dc=com", "Staff",
                "cn=Alice,ou=users,dc=example,dc=com");

        List<LdapGroup> found = groupService.searchGroups(dc, "(cn=Staff)", GROUPS_OU);
        assertThat(found).hasSize(1);
    }

    // ── getGroup ──────────────────────────────────────────────────────────────

    @Test
    void getGroup_existingGroup_returnsGroup() throws Exception {
        String dn = "cn=Staff,ou=groups,dc=example,dc=com";
        addGroup(dn, "Staff", "cn=Alice,ou=users,dc=example,dc=com");

        LdapGroup group = groupService.getGroup(dc, dn);

        assertThat(group.getDn()).isEqualTo(dn);
        assertThat(group.getValues("cn")).contains("Staff");
    }

    @Test
    void getGroup_notFound_throwsResourceNotFoundException() {
        assertThatThrownBy(() ->
                groupService.getGroup(dc, "cn=NoGroup,ou=groups,dc=example,dc=com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getGroup_withAttributeFilter_returnsOnlyRequestedAttributes() throws Exception {
        String dn = "cn=Staff,ou=groups,dc=example,dc=com";
        addGroup(dn, "Staff", "cn=Alice,ou=users,dc=example,dc=com");

        LdapGroup group = groupService.getGroup(dc, dn, "cn");

        assertThat(group.getValues("cn")).contains("Staff");
        // member not requested → absent
        assertThat(group.getValues("member")).isEmpty();
    }

    // ── createGroup ───────────────────────────────────────────────────────────

    @Test
    void createGroup_addsEntryToDirectory() throws Exception {
        String dn = "cn=Engineering,ou=groups,dc=example,dc=com";

        groupService.createGroup(dc, dn, Map.of(
                "objectClass", List.of("top", "groupOfNames"),
                "cn",          List.of("Engineering"),
                "member",      List.of("cn=Alice,ou=users,dc=example,dc=com")));

        LdapGroup fetched = groupService.getGroup(dc, dn);
        assertThat(fetched.getDn()).isEqualTo(dn);
        assertThat(fetched.getValues("cn")).contains("Engineering");
    }

    // ── deleteGroup ───────────────────────────────────────────────────────────

    @Test
    void deleteGroup_removesEntryFromDirectory() throws Exception {
        String dn = "cn=Temp,ou=groups,dc=example,dc=com";
        addGroup(dn, "Temp", "cn=Alice,ou=users,dc=example,dc=com");

        groupService.deleteGroup(dc, dn);

        assertThatThrownBy(() -> groupService.getGroup(dc, dn))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── addMember ─────────────────────────────────────────────────────────────

    @Test
    void addMember_appendsMemberValue() throws Exception {
        String groupDn   = "cn=Staff,ou=groups,dc=example,dc=com";
        String memberA   = "cn=Alice,ou=users,dc=example,dc=com";
        String memberB   = "cn=Bob,ou=users,dc=example,dc=com";
        addGroup(groupDn, "Staff", memberA);

        groupService.addMember(dc, groupDn, "member", memberB);

        List<String> members = groupService.getMembers(dc, groupDn, "member");
        assertThat(members).contains(memberA, memberB);
    }

    // ── removeMember ──────────────────────────────────────────────────────────

    @Test
    void removeMember_deletesMemberValue() throws Exception {
        String groupDn = "cn=Staff,ou=groups,dc=example,dc=com";
        String memberA = "cn=Alice,ou=users,dc=example,dc=com";
        String memberB = "cn=Bob,ou=users,dc=example,dc=com";
        // groupOfNames requires at least one member, so add two
        inMemoryServer.add(new Entry(groupDn,
                new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", "Staff"),
                new Attribute("member", memberA, memberB)));

        groupService.removeMember(dc, groupDn, "member", memberB);

        List<String> members = groupService.getMembers(dc, groupDn, "member");
        assertThat(members).containsOnly(memberA);
        assertThat(members).doesNotContain(memberB);
    }

    // ── getMembers ────────────────────────────────────────────────────────────

    @Test
    void getMembers_returnsAllMemberValues() throws Exception {
        String groupDn = "cn=Dept,ou=groups,dc=example,dc=com";
        String m1 = "cn=Alice,ou=users,dc=example,dc=com";
        String m2 = "cn=Bob,ou=users,dc=example,dc=com";
        inMemoryServer.add(new Entry(groupDn,
                new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", "Dept"),
                new Attribute("member", m1, m2)));

        List<String> members = groupService.getMembers(dc, groupDn, "member");

        assertThat(members).containsExactlyInAnyOrder(m1, m2);
    }

    @Test
    void getMembers_emptyAttribute_returnsEmptyList() throws Exception {
        // groupOfNames requires at least one member, use posixGroup (memberUid is optional)
        String groupDn = "cn=EmptyGroup,ou=groups,dc=example,dc=com";
        inMemoryServer.add(new Entry(groupDn,
                new Attribute("objectClass", "top", "posixGroup"),
                new Attribute("cn", "EmptyGroup"),
                new Attribute("gidNumber", "1000")));

        List<String> members = groupService.getMembers(dc, groupDn, "memberUid");

        assertThat(members).isEmpty();
    }



    // ── getNestedMembers — per-directory-type dispatch ───────────────────────

    @Test
    void getNestedMembers_oudDirectoryType_usesIsMemberOfFilter() throws Exception {
        // OUD/OpenDJ populate `isMemberOf` on user entries with the full
        // transitive group closure. The InMemoryDirectoryServer doesn't
        // compute it automatically (it's only an operational attribute on
        // real OUD), but with schema validation off we can put it on the
        // user entries explicitly — exactly the shape OUD would emit.
        String engineering   = "cn=engineering,ou=groups,dc=example,dc=com";
        String allTechStaff  = "cn=allTechStaff,ou=groups,dc=example,dc=com";
        String alice         = "cn=Alice,ou=users,dc=example,dc=com";
        String bob           = "cn=Bob,ou=users,dc=example,dc=com";
        String carol         = "cn=Carol,ou=users,dc=example,dc=com";  // not in the target group

        inMemoryServer.add(new Entry(alice,
                new Attribute("objectClass", "top", "inetOrgPerson"),
                new Attribute("cn", "Alice"),
                new Attribute("sn", "Smith"),
                // Both the direct team group and the transitive aggregate —
                // matches what OpenDJ returned for alice.smith in the live
                // P0 verification (engineering + allTechStaff).
                new Attribute("isMemberOf", engineering, allTechStaff)));
        inMemoryServer.add(new Entry(bob,
                new Attribute("objectClass", "top", "inetOrgPerson"),
                new Attribute("cn", "Bob"),
                new Attribute("sn", "Jones"),
                new Attribute("isMemberOf", engineering, allTechStaff)));
        inMemoryServer.add(new Entry(carol,
                new Attribute("objectClass", "top", "inetOrgPerson"),
                new Attribute("cn", "Carol"),
                new Attribute("sn", "Davis"),
                // Not in engineering; in a different group only.
                new Attribute("isMemberOf",
                        "cn=design,ou=groups,dc=example,dc=com")));

        dc.setDirectoryType(com.ldapportal.entity.enums.DirectoryType.ORACLE_UNIFIED_DIRECTORY);

        // Direct group — alice + bob, carol excluded.
        List<String> direct = groupService.getNestedMembers(dc, engineering);
        assertThat(direct).containsExactlyInAnyOrder(alice, bob);

        // Transitive aggregate — same two users, this time because they
        // hit it indirectly via the engineering team. The whole point of
        // the OUD branch is that we get them in one query rather than
        // walking through cn=engineering first.
        List<String> transitive = groupService.getNestedMembers(dc, allTechStaff);
        assertThat(transitive).containsExactlyInAnyOrder(alice, bob);
    }

    @Test
    void getNestedMembers_genericDirectoryType_recursivelyResolvesNested() throws Exception {
        // Regression for the default (non-AD, non-OUD) path — the
        // recursive walk should descend through a nested group and
        // surface both the direct and transitive members. Existing test
        // coverage of getNestedMembers was zero before P4; covering the
        // generic branch here so we don't have a one-sided assertion.
        String alice    = "cn=Alice,ou=users,dc=example,dc=com";
        String bob      = "cn=Bob,ou=users,dc=example,dc=com";
        String teamGrp  = "cn=engineering,ou=groups,dc=example,dc=com";
        String topGrp   = "cn=allTechStaff,ou=groups,dc=example,dc=com";

        // Two users with no membership attributes — the recursive walk
        // works off `member` on the groups, not `isMemberOf` on users.
        inMemoryServer.add(new Entry(alice,
                new Attribute("objectClass", "top", "inetOrgPerson"),
                new Attribute("cn", "Alice"),
                new Attribute("sn", "Smith")));
        inMemoryServer.add(new Entry(bob,
                new Attribute("objectClass", "top", "inetOrgPerson"),
                new Attribute("cn", "Bob"),
                new Attribute("sn", "Jones")));
        // Team group with both users as direct members.
        addGroup(teamGrp, "engineering", alice);
        inMemoryServer.modify(teamGrp,
                new com.unboundid.ldap.sdk.Modification(
                        com.unboundid.ldap.sdk.ModificationType.ADD, "member", bob));
        // Aggregate whose only member is the team group.
        addGroup(topGrp, "allTechStaff", teamGrp);

        dc.setDirectoryType(com.ldapportal.entity.enums.DirectoryType.GENERIC);

        List<String> transitive = groupService.getNestedMembers(dc, topGrp);
        // Should include the nested group itself + both users discovered
        // by recursing into it.
        assertThat(transitive).contains(teamGrp, alice, bob);
    }

    @Test
    void getNestedMembers_oudBranch_normalisesCallerSuppliedDn() throws Exception {
        // OpenDJ's default attribute matching on isMemberOf is case- and
        // whitespace-sensitive string equality. Without normalising the
        // caller-supplied DN, a query for 'CN=Engineering, OU=Groups,
        // DC=example, DC=com' would silently miss entries whose stored
        // isMemberOf value is 'cn=engineering,ou=groups,dc=example,dc=com'.
        // The OUD branch now runs the input through DN.toNormalizedString
        // before encoding it into the filter.
        String engineering = "cn=engineering,ou=groups,dc=example,dc=com";
        String alice       = "cn=Alice,ou=users,dc=example,dc=com";

        inMemoryServer.add(new com.unboundid.ldap.sdk.Entry(alice,
                new com.unboundid.ldap.sdk.Attribute("objectClass", "top", "inetOrgPerson"),
                new com.unboundid.ldap.sdk.Attribute("cn", "Alice"),
                new com.unboundid.ldap.sdk.Attribute("sn", "Smith"),
                // Server stores the lowercase canonical form.
                new com.unboundid.ldap.sdk.Attribute("isMemberOf", engineering)));

        dc.setDirectoryType(com.ldapportal.entity.enums.DirectoryType.ORACLE_UNIFIED_DIRECTORY);

        // Caller passes a different case/spacing variant of the same DN.
        // Without normalisation the in-memory server's string-equality
        // matcher would return zero hits.
        List<String> hits = groupService.getNestedMembers(
                dc, "CN=Engineering, OU=Groups, DC=example, DC=com");
        assertThat(hits).containsExactly(alice);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void addGroup(String dn, String cn, String firstMember) throws Exception {
        inMemoryServer.add(new Entry(dn,
                new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", cn),
                new Attribute("member", firstMember)));
    }

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
