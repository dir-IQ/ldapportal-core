// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behaviour of the parallel, short-TTL-cached scope counter that backs the
 * admin dashboard's per-profile user/group counts: scope dedup, cross-call
 * caching, failure passthrough (and that failures are <em>not</em> cached),
 * and the disabled-directory shortcut.
 */
@ExtendWith(MockitoExtension.class)
class ScopeCountServiceTest {

    @Mock private LdapUserService userService;
    @Mock private LdapGroupService groupService;

    /** TTL long enough that nothing expires mid-test; small pool. */
    private ScopeCountService newService() {
        return new ScopeCountService(userService, groupService, 60, 4);
    }

    private DirectoryConnection directory(boolean enabled) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setDisplayName("DIR");
        dc.setEnabled(enabled);
        return dc;
    }

    private static ScopeCountService.ScopeRequest request(UUID profileId, DirectoryConnection dc,
                                                          String userDn, String groupDn) {
        return new ScopeCountService.ScopeRequest(profileId, dc, userDn, groupDn);
    }

    @Test
    void counts_users_and_groups_per_profile() {
        ScopeCountService svc = newService();
        DirectoryConnection dc = directory(true);
        when(userService.countUsers(eq(dc), eq("ou=People"), anyLong())).thenReturn(42L);
        when(groupService.countGroups(eq(dc), eq("ou=Groups"), anyLong())).thenReturn(7L);

        UUID profile = UUID.randomUUID();
        var result = svc.countAll(List.of(request(profile, dc, "ou=People", "ou=Groups")));

        assertThat(result.get(profile).users()).isEqualTo(42L);
        assertThat(result.get(profile).groups()).isEqualTo(7L);
    }

    @Test
    void dedupes_a_shared_scope_to_one_query_each() {
        ScopeCountService svc = newService();
        DirectoryConnection dc = directory(true);
        when(userService.countUsers(eq(dc), eq("ou=People"), anyLong())).thenReturn(10L);
        when(groupService.countGroups(eq(dc), eq("ou=People"), anyLong())).thenReturn(3L);

        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        // Two profiles sharing the same (directory, user OU, group OU).
        var result = svc.countAll(List.of(
                request(p1, dc, "ou=People", "ou=People"),
                request(p2, dc, "ou=People", "ou=People")));

        assertThat(result.get(p1).users()).isEqualTo(10L);
        assertThat(result.get(p2).users()).isEqualTo(10L);
        verify(userService, times(1)).countUsers(eq(dc), eq("ou=People"), anyLong());
        verify(groupService, times(1)).countGroups(eq(dc), eq("ou=People"), anyLong());
    }

    @Test
    void serves_a_second_call_from_cache_within_ttl() {
        ScopeCountService svc = newService();
        DirectoryConnection dc = directory(true);
        when(userService.countUsers(eq(dc), eq("ou=People"), anyLong())).thenReturn(5L);
        when(groupService.countGroups(eq(dc), eq("ou=People"), anyLong())).thenReturn(2L);

        UUID profile = UUID.randomUUID();
        var req = List.of(request(profile, dc, "ou=People", "ou=People"));
        svc.countAll(req);
        var second = svc.countAll(req);

        assertThat(second.get(profile).users()).isEqualTo(5L);
        assertThat(second.get(profile).groups()).isEqualTo(2L);
        // Second call hit the cache — no extra LDAP work.
        verify(userService, times(1)).countUsers(eq(dc), eq("ou=People"), anyLong());
        verify(groupService, times(1)).countGroups(eq(dc), eq("ou=People"), anyLong());
    }

    @Test
    void returns_minus_one_on_failure_and_does_not_cache_it() {
        ScopeCountService svc = newService();
        DirectoryConnection dc = directory(true);
        when(userService.countUsers(eq(dc), eq("ou=People"), anyLong()))
                .thenThrow(new RuntimeException("ldap down"))
                .thenReturn(99L);
        when(groupService.countGroups(eq(dc), eq("ou=People"), anyLong())).thenReturn(1L);

        UUID profile = UUID.randomUUID();
        var req = List.of(request(profile, dc, "ou=People", "ou=People"));

        var first = svc.countAll(req);
        assertThat(first.get(profile).users()).isEqualTo(-1L);

        // The -1 failure wasn't cached, so a second call retries and succeeds.
        var second = svc.countAll(req);
        assertThat(second.get(profile).users()).isEqualTo(99L);
        verify(userService, times(2)).countUsers(eq(dc), eq("ou=People"), anyLong());
    }

    @Test
    void disabled_directory_yields_zero_without_touching_ldap() {
        ScopeCountService svc = newService();
        DirectoryConnection dc = directory(false);

        UUID profile = UUID.randomUUID();
        var result = svc.countAll(List.of(request(profile, dc, "ou=People", "ou=Groups")));

        assertThat(result.get(profile).users()).isZero();
        assertThat(result.get(profile).groups()).isZero();
        verifyNoInteractions(userService, groupService);
    }

    @Test
    void empty_request_list_returns_an_empty_map() {
        ScopeCountService svc = newService();
        assertThat(svc.countAll(List.of())).isEmpty();
        verifyNoInteractions(userService, groupService);
    }
}
