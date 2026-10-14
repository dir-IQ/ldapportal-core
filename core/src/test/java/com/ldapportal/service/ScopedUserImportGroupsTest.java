// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.csv.GroupMemberChange.Outcome;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.service.UserImportGroups.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScopedUserImportGroups}: group values (cn or DN) resolved within the
 * caller's scope with the attribute members are written to, the
 * manage-members check, and adds handed to the governed membership path.
 */
@ExtendWith(MockitoExtension.class)
class ScopedUserImportGroupsTest {

    @Mock private PermissionService permissionService;
    @Mock private LdapGroupService groupService;

    private final UUID dirId = UUID.randomUUID();
    private final AuthPrincipal admin = new AuthPrincipal(PrincipalType.ADMIN, UUID.randomUUID(), "admin");
    private final List<Object[]> written = new ArrayList<>();
    private DirectoryConnection dc;
    private ScopedUserImportGroups groups;

    private static final String GROUPS = "ou=groups,dc=example,dc=com";
    private static final String ADMINS = "cn=admins," + GROUPS;

    @BeforeEach
    void setUp() {
        dc = new DirectoryConnection();
        dc.setId(dirId);
        dc.setBaseDn("dc=example,dc=com");
        groups = new ScopedUserImportGroups(dc, dirId, admin, permissionService, groupService,
                (memberDn, memberUid, changes) -> {
                    written.add(new Object[]{memberDn, memberUid, changes});
                    return MembershipChangeResult.of(List.of(MembershipChangeResult.Item.applied(changes.get(0))));
                });
    }

    private static LdapGroup group(String dn, String... objectClasses) {
        return new LdapGroup(dn, Map.of("objectClass", List.of(objectClasses)));
    }

    private void groupsUnder(String base, LdapGroup... found) {
        when(permissionService.resolveSearchBaseDns(admin, dirId, null)).thenReturn(List.of(base));
        when(groupService.searchGroups(eq(dc), anyString(), eq(base), eq(2), eq("objectClass")))
                .thenReturn(List.of(found));
    }

    @Test
    void cn_resolvesToTheSingleGroupInScope_withItsMemberAttribute_andIsCached() {
        groupsUnder(GROUPS, group(ADMINS, "groupOfUniqueNames"));
        when(permissionService.isDnWithinScope(admin, dirId, ADMINS)).thenReturn(true);

        Target t = groups.resolve("admins");
        assertThat(t).isEqualTo(Target.of(ADMINS, "uniqueMember"));
        assertThat(groups.resolve("ADMINS")).isSameAs(t);
        verify(groupService, times(1)).searchGroups(eq(dc), anyString(), eq(GROUPS), eq(2), eq("objectClass"));
    }

    @Test
    void cn_withNoMatch_orSeveral_isReported() {
        groupsUnder(GROUPS);
        assertThat(groups.resolve("ghost").problem()).isEqualTo(Outcome.MEMBER_NOT_FOUND);

        when(groupService.searchGroups(eq(dc), anyString(), eq(GROUPS), eq(2), eq("objectClass")))
                .thenReturn(List.of(group(ADMINS, "groupOfNames"), group("cn=admins,ou=other,dc=example,dc=com", "groupOfNames")));
        Target t = groups.resolve("admins");
        assertThat(t.problem()).isEqualTo(Outcome.AMBIGUOUS);
        assertThat(t.note()).contains("use the group's DN");
    }

    @Test
    void cn_overlappingBasesReturningTheSameGroup_isNotAmbiguous() {
        String other = "dc=example,dc=com";
        when(permissionService.resolveSearchBaseDns(admin, dirId, null)).thenReturn(List.of(GROUPS, other));
        when(groupService.searchGroups(eq(dc), anyString(), eq(GROUPS), eq(2), eq("objectClass")))
                .thenReturn(List.of(group(ADMINS, "groupOfNames")));
        when(groupService.searchGroups(eq(dc), anyString(), eq(other), eq(2), eq("objectClass")))
                .thenReturn(List.of(group("CN=Admins,OU=Groups,DC=example,DC=com", "groupOfNames")));
        when(permissionService.isDnWithinScope(admin, dirId, ADMINS)).thenReturn(true);

        assertThat(groups.resolve("admins")).isEqualTo(Target.of(ADMINS, "member"));
    }

    @Test
    void dn_mustBeInScope_andAGroup() {
        String outside = "cn=finance,ou=restricted,dc=example,dc=com";
        when(permissionService.isDnWithinScope(admin, dirId, outside)).thenReturn(false);
        assertThat(groups.resolve(outside).problem()).isEqualTo(Outcome.OUT_OF_SCOPE);

        String person = "uid=bob,ou=people,dc=example,dc=com";
        when(permissionService.isDnWithinScope(admin, dirId, person)).thenReturn(true);
        when(groupService.getGroup(dc, person)).thenReturn(group(person, "inetOrgPerson"));
        assertThat(groups.resolve(person).problem()).isEqualTo(Outcome.INVALID);

        String missing = "cn=gone," + GROUPS;
        when(permissionService.isDnWithinScope(admin, dirId, missing)).thenReturn(true);
        when(groupService.getGroup(dc, missing)).thenThrow(new ResourceNotFoundException("Group", missing));
        assertThat(groups.resolve(missing).problem()).isEqualTo(Outcome.MEMBER_NOT_FOUND);

        String posix = "cn=devs," + GROUPS;
        when(permissionService.isDnWithinScope(admin, dirId, posix)).thenReturn(true);
        when(groupService.getGroup(dc, posix)).thenReturn(group(posix, "posixGroup"));
        assertThat(groups.resolve(posix)).isEqualTo(Target.of(posix, "memberUid"));
    }

    @Test
    void mayManageMembers_followsTheFeatureCheck() {
        assertThat(groups.mayManageMembers()).isTrue();

        ScopedUserImportGroups denied = new ScopedUserImportGroups(dc, dirId, admin, permissionService,
                groupService, (m, u, c) -> null);
        doThrow(new AccessDeniedException("no")).when(permissionService)
                .requireFeature(admin, dirId, FeatureKey.GROUP_MANAGE_MEMBERS);
        assertThat(denied.mayManageMembers()).isFalse();
    }

    @Test
    void add_handsTheChangesToTheMembershipWriter() {
        var change = new MembershipChangeRequest.Change(ADMINS, "member", MembershipChangeRequest.Op.ADD);
        groups.add("uid=alice,ou=people,dc=example,dc=com", "alice", List.of(change));

        assertThat(written).hasSize(1);
        assertThat(written.get(0)[0]).isEqualTo("uid=alice,ou=people,dc=example,dc=com");
        assertThat(written.get(0)[1]).isEqualTo("alice");
        assertThat(written.get(0)[2]).isEqualTo(List.of(change));
    }
}
