// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.model.LdapGroup;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link UserGroupIndex}: a user's direct groups, worked out from the group entries. */
class UserGroupIndexTest {

    private static final String JSMITH = "uid=jsmith,ou=people,dc=x";

    /** Lower-cased keys, as LdapGroupService returns them. */
    private static LdapGroup group(String dn, String cn, String attr, String... members) {
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("cn", List.of(cn));
        attrs.put(attr.toLowerCase(), Arrays.asList(members));
        return new LdapGroup(dn, attrs);
    }

    @Test
    void listsDirectGroupsFromMemberUniqueMemberAndMemberUid() {
        UserGroupIndex index = UserGroupIndex.of(List.of(
                group("cn=admins,ou=g,dc=x", "admins", "member", "UID=JSmith, OU=People, DC=x"),
                group("cn=support,ou=g,dc=x", "support", "uniqueMember", JSMITH),
                group("cn=devs,ou=g,dc=x", "devs", "memberUid", "JSMITH"),
                group("cn=other,ou=g,dc=x", "other", "member", "uid=adoe,ou=people,dc=x")));

        assertThat(index.groupsOf(JSMITH, "jsmith")).containsExactly("admins", "support", "devs");
        // Without a uid, memberUid groups can't be matched.
        assertThat(index.groupsOf(JSMITH, null)).containsExactly("admins", "support");
        assertThat(index.groupsOf("uid=nobody,dc=x", "nobody")).isEmpty();
    }

    @Test
    void usesTheDnWhenAnotherGroupSharesTheCn() {
        UserGroupIndex index = UserGroupIndex.of(List.of(
                group("cn=engineering,ou=a,dc=x", "engineering", "member", JSMITH),
                group("cn=Engineering,ou=b,dc=x", "Engineering", "member", "uid=adoe,ou=people,dc=x"),
                group("cn=vpn,ou=g,dc=x", "vpn", "member", JSMITH)));

        assertThat(index.groupsOf(JSMITH, null)).containsExactly("cn=engineering,ou=a,dc=x", "vpn");
    }

    @Test
    void buildsFromTheGroupsUnderEachBase_countingOverlapsOnce() {
        LdapGroupService groupService = mock(LdapGroupService.class);
        DirectoryConnection dc = new DirectoryConnection();
        dc.setBaseDn("dc=x");
        LdapGroup admins = group("cn=admins,ou=g,dc=x", "admins", "member", JSMITH);
        when(groupService.searchGroups(eq(dc), anyString(), eq("ou=g,dc=x"),
                eq("cn"), eq("member"), eq("uniqueMember"), eq("memberUid"))).thenReturn(List.of(admins));
        when(groupService.searchGroups(eq(dc), anyString(), eq("dc=x"),
                eq("cn"), eq("member"), eq("uniqueMember"), eq("memberUid"))).thenReturn(List.of(admins));

        UserGroupIndex index = UserGroupIndex.build(groupService, dc, List.of("ou=g,dc=x", "dc=x"));

        assertThat(index.groupsOf(JSMITH, null)).containsExactly("admins");
    }
}
