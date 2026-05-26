// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.directory;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.model.LdapUser;
import com.unboundid.ldap.sdk.LDAPConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LdapDirectoryProviderTest {

    @Mock private LdapUserService userService;
    @Mock private LdapGroupService groupService;
    @Mock private LdapConnectionFactory connectionFactory;

    private LdapDirectoryProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LdapDirectoryProvider(userService, groupService, connectionFactory);
    }

    private DirectoryConnection makeConnection() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(DirectoryType.GENERIC);
        dc.setDisplayName("Test LDAP");
        return dc;
    }

    // ── supportedTypes ───────────────────────────────────────────────────────

    @Test
    void supportedTypes_includesAllLdapVariants() {
        assertThat(provider.supportedTypes()).containsExactlyInAnyOrder(
                DirectoryType.GENERIC,
                DirectoryType.ACTIVE_DIRECTORY,
                DirectoryType.OPENLDAP,
                // Phase 1 of the ITDS support plan claims this directory type
                // for the LDAP provider — same generic code paths apply until
                // later phases add ibm-allMembers, IbmChangelogStrategy, etc.
                DirectoryType.IBM_DIRECTORY_SERVER);
    }

    // ── searchUsers ──────────────────────────────────────────────────────────

    @Test
    void searchUsers_mapsLdapUserToDirectoryUser() {
        DirectoryConnection dc = makeConnection();
        LdapUser ldapUser = new LdapUser("uid=alice,ou=Staff,dc=corp", Map.of(
                "cn", List.of("Alice Smith"),
                "uid", List.of("alice"),
                "mail", List.of("alice@corp.com"),
                "displayname", List.of("Alice S")));

        when(userService.searchUsers(eq(dc), anyString(), any(), anyInt(), any(String[].class)))
                .thenReturn(List.of(ldapUser));

        List<DirectoryUser> result = provider.searchUsers(dc, null, 100);

        assertThat(result).hasSize(1);
        DirectoryUser u = result.get(0);
        assertThat(u.id()).isEqualTo("uid=alice,ou=Staff,dc=corp");
        assertThat(u.displayName()).isEqualTo("Alice S");
        assertThat(u.loginName()).isEqualTo("alice");
        assertThat(u.email()).isEqualTo("alice@corp.com");
    }

    @Test
    void searchUsers_requestsIdentityResolutionAttributes() {
        // Regression: the cross-directory identity resolver (ee.hybrid)
        // reads employeeNumber / employeeID / userPrincipalName off each
        // candidate to drive secondary-key (EMPLOYEE_ID) and tertiary-key
        // (USER_PRINCIPAL_NAME) matching. UnboundID's SearchRequest only
        // returns attributes the caller asked for, so if these aren't in
        // the requested list they come back null on the Candidate and
        // priority-2 / priority-3 matching silently no-ops.
        DirectoryConnection dc = makeConnection();
        ArgumentCaptor<String[]> attrsCaptor = ArgumentCaptor.forClass(String[].class);
        when(userService.searchUsers(eq(dc), anyString(), any(), anyInt(), attrsCaptor.capture()))
                .thenReturn(List.of());

        provider.searchUsers(dc, null, 100);

        assertThat(attrsCaptor.getValue())
                .as("USER_ATTRS must include attributes the cross-directory resolver depends on")
                .contains("employeeNumber", "employeeID", "userPrincipalName");
    }

    @Test
    void searchUsers_propagatesIdentityResolutionAttributesOnDirectoryUser() {
        // Belt-and-braces companion to the request-attrs test: even if
        // someone removes them from USER_ATTRS, this test catches the
        // downstream symptom — the values must surface on
        // DirectoryUser.attributes() so CrossDirectorySearchService can
        // pick them up with firstAttributeValue(...).
        DirectoryConnection dc = makeConnection();
        LdapUser ldapUser = new LdapUser("uid=danielle,dc=corp", Map.of(
                "cn", List.of("Danielle Perez"),
                "uid", List.of("danielle"),
                "mail", List.of("danielle.perez@corp.com"),
                "employeenumber", List.of("EMP000200"),
                "userprincipalname", List.of("danielle@corp.local")));

        when(userService.searchUsers(eq(dc), anyString(), any(), anyInt(), any(String[].class)))
                .thenReturn(List.of(ldapUser));

        DirectoryUser u = provider.searchUsers(dc, null, 100).get(0);
        assertThat(u.attributes()).containsKeys("employeenumber", "userprincipalname");
        assertThat(u.attributes().get("employeenumber")).containsExactly("EMP000200");
    }

    @Test
    void searchUsers_fallsBackToCn_whenDisplayNameMissing() {
        DirectoryConnection dc = makeConnection();
        LdapUser ldapUser = new LdapUser("uid=bob,dc=corp", Map.of(
                "cn", List.of("Bob Jones"),
                "uid", List.of("bob")));

        when(userService.searchUsers(eq(dc), anyString(), any(), anyInt(), any(String[].class)))
                .thenReturn(List.of(ldapUser));

        List<DirectoryUser> result = provider.searchUsers(dc, null, 100);
        assertThat(result.get(0).displayName()).isEqualTo("Bob Jones");
    }

    // ── enabled detection ────────────────────────────────────────────────────

    @Test
    void searchUsers_detectsAdDisabledAccount() {
        DirectoryConnection dc = makeConnection();
        // userAccountControl 514 = 512 (normal) + 2 (ACCOUNTDISABLE)
        LdapUser ldapUser = new LdapUser("cn=disabled,dc=corp", Map.of(
                "cn", List.of("Disabled User"),
                "useraccountcontrol", List.of("514")));

        when(userService.searchUsers(eq(dc), anyString(), any(), anyInt(), any(String[].class)))
                .thenReturn(List.of(ldapUser));

        List<DirectoryUser> result = provider.searchUsers(dc, null, 100);
        assertThat(result.get(0).enabled()).isFalse();
    }

    @Test
    void searchUsers_detectsAdEnabledAccount() {
        DirectoryConnection dc = makeConnection();
        // userAccountControl 512 = normal account, enabled
        LdapUser ldapUser = new LdapUser("cn=active,dc=corp", Map.of(
                "cn", List.of("Active User"),
                "useraccountcontrol", List.of("512")));

        when(userService.searchUsers(eq(dc), anyString(), any(), anyInt(), any(String[].class)))
                .thenReturn(List.of(ldapUser));

        List<DirectoryUser> result = provider.searchUsers(dc, null, 100);
        assertThat(result.get(0).enabled()).isTrue();
    }

    @Test
    void searchUsers_detectsOpenLdapLockedAccount() {
        DirectoryConnection dc = makeConnection();
        LdapUser ldapUser = new LdapUser("uid=locked,dc=corp", Map.of(
                "cn", List.of("Locked User"),
                "uid", List.of("locked"),
                "nsaccountlock", List.of("true")));

        when(userService.searchUsers(eq(dc), anyString(), any(), anyInt(), any(String[].class)))
                .thenReturn(List.of(ldapUser));

        List<DirectoryUser> result = provider.searchUsers(dc, null, 100);
        assertThat(result.get(0).enabled()).isFalse();
    }

    @Test
    void searchUsers_defaultsToEnabled_whenNoDisableAttribute() {
        DirectoryConnection dc = makeConnection();
        LdapUser ldapUser = new LdapUser("uid=normal,dc=corp", Map.of(
                "cn", List.of("Normal User"),
                "uid", List.of("normal")));

        when(userService.searchUsers(eq(dc), anyString(), any(), anyInt(), any(String[].class)))
                .thenReturn(List.of(ldapUser));

        List<DirectoryUser> result = provider.searchUsers(dc, null, 100);
        assertThat(result.get(0).enabled()).isTrue();
    }

    // ── searchGroups ─────────────────────────────────────────────────────────

    @Test
    void searchGroups_mapsLdapGroupToDirectoryGroup() {
        DirectoryConnection dc = makeConnection();
        LdapGroup ldapGroup = new LdapGroup("cn=devs,ou=Groups,dc=corp", Map.of(
                "cn", List.of("devs"),
                "description", List.of("Developers"),
                "member", List.of("uid=alice,dc=corp", "uid=bob,dc=corp")));

        when(groupService.searchGroups(eq(dc), anyString(), any(), anyInt(), any(String[].class)))
                .thenReturn(List.of(ldapGroup));

        List<DirectoryGroup> result = provider.searchGroups(dc, null, 100);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("cn=devs,ou=Groups,dc=corp");
        assertThat(result.get(0).name()).isEqualTo("devs");
        assertThat(result.get(0).description()).isEqualTo("Developers");
        assertThat(result.get(0).memberCount()).isEqualTo(2);
    }

    // ── getGroupMembers ──────────────────────────────────────────────────────

    @Test
    void getGroupMembers_returnsAllMemberValues() {
        DirectoryConnection dc = makeConnection();
        LdapGroup group = new LdapGroup("cn=team,dc=corp", Map.of(
                "cn", List.of("team"),
                "member", List.of("uid=alice,dc=corp", "uid=bob,dc=corp")));

        when(groupService.getGroup(eq(dc), eq("cn=team,dc=corp"), any(String[].class)))
                .thenReturn(group);

        List<String> members = provider.getGroupMembers(dc, "cn=team,dc=corp");
        assertThat(members).containsExactly("uid=alice,dc=corp", "uid=bob,dc=corp");
    }

    // ── pollAuditEvents ──────────────────────────────────────────────────────

    @Test
    void pollAuditEvents_returnsEmpty_ldapUsesChangelogReader() {
        assertThat(provider.pollAuditEvents(makeConnection(), null)).isEmpty();
    }

    // ── testConnection ───────────────────────────────────────────────────────

    @Test
    void testConnection_returnsNull_onSuccess() throws Exception {
        DirectoryConnection dc = makeConnection();
        // LDAPConnection is final — use a real disconnected instance (close() is safe)
        when(connectionFactory.openUnboundConnection(dc)).thenReturn(new LDAPConnection());

        assertThat(provider.testConnection(dc)).isNull();
    }

    @Test
    void testConnection_returnsErrorMessage_onFailure() throws Exception {
        DirectoryConnection dc = makeConnection();
        when(connectionFactory.openUnboundConnection(dc))
                .thenThrow(new RuntimeException("Connection refused"));

        assertThat(provider.testConnection(dc)).isEqualTo("Connection refused");
    }
}
