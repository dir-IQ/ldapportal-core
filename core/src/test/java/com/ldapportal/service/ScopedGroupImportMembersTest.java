// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.csv.GroupMemberChange.Outcome;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.service.GroupImportMembers.ExistingGroup;
import com.ldapportal.service.GroupImportMembers.Member;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScopedGroupImportMembers}: uid and DN member values resolved within
 * the caller's scope, groups classified with the attribute they use, and
 * writes handed to the governed membership path.
 */
@ExtendWith(MockitoExtension.class)
class ScopedGroupImportMembersTest {

    @Mock private PermissionService permissionService;
    @Mock private LdapUserService userService;
    @Mock private LdapGroupService groupService;

    private final UUID dirId = UUID.randomUUID();
    private final AuthPrincipal admin = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "admin");
    private final List<Object[]> written = new ArrayList<>();
    private DirectoryConnection dc;
    private ScopedGroupImportMembers members;

    private static final String PEOPLE = "ou=people,dc=example,dc=com";
    private static final String ALICE = "uid=alice," + PEOPLE;

    @BeforeEach
    void setUp() {
        dc = new DirectoryConnection();
        dc.setId(dirId);
        dc.setBaseDn("dc=example,dc=com");
        members = new ScopedGroupImportMembers(dc, dirId, admin, permissionService, userService, groupService,
                (memberDn, memberUid, changes) -> {
                    written.add(new Object[]{memberDn, memberUid, changes});
                    return MembershipChangeResult.of(List.of(MembershipChangeResult.Item.applied(changes.get(0))));
                });
    }

    private static LdapUser user(String dn) {
        return new LdapUser(dn, Map.of());
    }

    @Test
    void uid_resolvesToTheSingleUserUnderTheCallersBases_andIsCached() {
        when(permissionService.resolveSearchBaseDns(admin, dirId, null)).thenReturn(List.of(PEOPLE));
        when(userService.searchUsers(eq(dc), anyString(), eq(PEOPLE), eq(2), eq("1.1")))
                .thenReturn(List.of(user(ALICE)));
        when(permissionService.isDnWithinScope(admin, dirId, ALICE)).thenReturn(true);

        Member m = members.resolveMember("alice");
        members.resolveMember("ALICE");

        assertThat(m).isEqualTo(Member.of(ALICE, "alice"));
        verify(userService, times(1)).searchUsers(any(), anyString(), anyString(), anyInt(), any(String[].class));
    }

    @Test
    void uid_withNoMatch_orSeveral_isReported() {
        when(permissionService.resolveSearchBaseDns(admin, dirId, null)).thenReturn(List.of(PEOPLE));
        when(userService.searchUsers(eq(dc), anyString(), eq(PEOPLE), eq(2), eq("1.1")))
                .thenReturn(List.of())
                .thenReturn(List.of(user(ALICE), user("uid=alice,ou=other,dc=example,dc=com")));

        assertThat(members.resolveMember("nobody").problem()).isEqualTo(Outcome.MEMBER_NOT_FOUND);
        assertThat(members.resolveMember("alice").problem()).isEqualTo(Outcome.AMBIGUOUS);
    }

    @Test
    void dn_outsideTheCallersScope_isReportedWithoutALookup() {
        when(permissionService.isDnWithinScope(admin, dirId, ALICE)).thenReturn(false);

        assertThat(members.resolveMember(ALICE).problem()).isEqualTo(Outcome.OUT_OF_SCOPE);
        verify(userService, times(0)).getUser(any(), anyString(), any(String[].class));
    }

    @Test
    void dn_thatDoesNotExist_isNotFound() {
        when(permissionService.isDnWithinScope(admin, dirId, ALICE)).thenReturn(true);
        when(userService.getUser(dc, ALICE, "uid")).thenThrow(new ResourceNotFoundException("LDAP user", ALICE));

        assertThat(members.resolveMember(ALICE).problem()).isEqualTo(Outcome.MEMBER_NOT_FOUND);
    }

    @Test
    void dn_resolvesWithItsUid() {
        when(permissionService.isDnWithinScope(admin, dirId, ALICE)).thenReturn(true);
        when(userService.getUser(dc, ALICE, "uid")).thenReturn(new LdapUser(ALICE, Map.of("uid", List.of("alice"))));

        assertThat(members.resolveMember(ALICE)).isEqualTo(Member.of(ALICE, "alice"));
    }

    @Test
    void findGroup_picksTheAttributeTheGroupUses_andFlagsNonGroups() {
        String g1 = "cn=g1,ou=groups,dc=example,dc=com";
        String g2 = "cn=g2,ou=groups,dc=example,dc=com";
        String person = "cn=p,ou=groups,dc=example,dc=com";
        String missing = "cn=none,ou=groups,dc=example,dc=com";
        when(groupService.getGroup(eq(dc), eq(g1), any(String[].class))).thenReturn(new LdapGroup(g1, Map.of(
                "objectclass", List.of("groupOfUniqueNames"), "uniquemember", List.of(ALICE))));
        when(groupService.getGroup(eq(dc), eq(g2), any(String[].class))).thenReturn(new LdapGroup(g2, Map.of(
                "objectclass", List.of("posixGroup"))));
        when(groupService.getGroup(eq(dc), eq(person), any(String[].class))).thenReturn(new LdapGroup(person, Map.of(
                "objectclass", List.of("inetOrgPerson"))));
        when(groupService.getGroup(eq(dc), eq(missing), any(String[].class)))
                .thenThrow(new ResourceNotFoundException("LDAP group", missing));

        ExistingGroup unique = members.findGroup(g1);
        assertThat(unique.isGroup()).isTrue();
        assertThat(unique.memberAttribute()).isEqualTo("uniqueMember");
        assertThat(unique.hasMember(Member.of("UID=Alice,ou=people,dc=example,dc=com", "alice"))).isTrue();
        assertThat(members.findGroup(g2).memberAttribute()).isEqualTo("memberUid");
        assertThat(members.findGroup(person).isGroup()).isFalse();
        assertThat(members.findGroup(missing)).isNull();
    }

    @Test
    void requireMemberChanges_checksGroupManageMembers() {
        members.requireMemberChanges();

        verify(permissionService).requireFeature(admin, dirId,
                com.ldapportal.entity.enums.FeatureKey.GROUP_MANAGE_MEMBERS);
    }

    @Test
    void apply_handsOneChangeToTheGovernedWriter_withTheUid() {
        MembershipChangeResult.Item item = members.apply(Member.of(ALICE, "alice"),
                "cn=g,ou=groups,dc=example,dc=com", "memberUid", MembershipChangeRequest.Op.ADD);

        assertThat(item.status()).isEqualTo(MembershipChangeResult.Status.APPLIED);
        assertThat(written).hasSize(1);
        assertThat(written.get(0)[0]).isEqualTo(ALICE);
        assertThat(written.get(0)[1]).isEqualTo("alice");
        assertThat(written.get(0)[2]).isEqualTo(List.of(new MembershipChangeRequest.Change(
                "cn=g,ou=groups,dc=example,dc=com", "memberUid", MembershipChangeRequest.Op.ADD)));
    }
}
