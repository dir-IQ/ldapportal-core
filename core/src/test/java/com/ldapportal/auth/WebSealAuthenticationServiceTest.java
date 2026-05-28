// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.Account;
import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.ApplicationSettingsRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSealAuthenticationServiceTest {

    @Mock private ApplicationSettingsRepository settingsRepo;
    @Mock private AccountRepository             accountRepo;
    @Mock private JwtTokenService               jwtTokenService;
    @Mock private HttpServletRequest            request;

    private WebSealAuthenticationService service;

    @BeforeEach
    void setUp() {
        service = new WebSealAuthenticationService(settingsRepo, accountRepo, jwtTokenService);
        lenient().when(jwtTokenService.issue(any(), any())).thenReturn("jwt-token");
        lenient().when(jwtTokenService.issue(any())).thenReturn("jwt-token");
    }

    // ── Feature-gate short-circuits ─────────────────────────────────────────

    @Test
    void returns_empty_when_no_settings_row() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.empty());
        assertThat(service.authenticate(request)).isEmpty();
    }

    @Test
    void returns_empty_when_WEBSEAL_not_in_enabled_auth_types() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings(Set.of(AccountType.LOCAL), "10.0.0.0/8")));
        assertThat(service.authenticate(request)).isEmpty();
    }

    @Test
    void returns_empty_when_trusted_proxy_list_is_empty_even_if_WEBSEAL_enabled() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings(EnumSet.of(AccountType.WEBSEAL), null)));
        assertThat(service.authenticate(request)).isEmpty();
    }

    // ── Trust boundary ──────────────────────────────────────────────────────

    @Test
    void returns_empty_when_peer_not_in_trusted_cidr() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings(EnumSet.of(AccountType.WEBSEAL), "10.0.0.0/8")));
        when(request.getRemoteAddr()).thenReturn("203.0.113.5");
        assertThat(service.authenticate(request)).isEmpty();
    }

    @Test
    void returns_empty_when_peer_trusted_but_no_iv_user_header() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings(EnumSet.of(AccountType.WEBSEAL), "10.0.0.0/8")));
        when(request.getRemoteAddr()).thenReturn("10.1.2.3");
        when(request.getHeader("iv-user")).thenReturn(null);
        assertThat(service.authenticate(request)).isEmpty();
    }

    @Test
    void returns_empty_when_iv_user_is_blank() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings(EnumSet.of(AccountType.WEBSEAL), "10.0.0.0/8")));
        when(request.getRemoteAddr()).thenReturn("10.1.2.3");
        when(request.getHeader("iv-user")).thenReturn("   ");
        assertThat(service.authenticate(request)).isEmpty();
    }

    // ── Pre-provisioning enforcement ────────────────────────────────────────

    @Test
    void rejects_when_no_matching_webseal_account() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings(EnumSet.of(AccountType.WEBSEAL), "10.0.0.0/8")));
        when(request.getRemoteAddr()).thenReturn("10.1.2.3");
        when(request.getHeader("iv-user")).thenReturn("alice");
        when(accountRepo.findByUsernameAndActiveTrue("alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("No active WebSEAL-linked admin account")
                .hasMessageContaining("alice");
    }

    @Test
    void rejects_when_matching_account_has_wrong_auth_type() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings(EnumSet.of(AccountType.WEBSEAL), "10.0.0.0/8")));
        when(request.getRemoteAddr()).thenReturn("10.1.2.3");
        when(request.getHeader("iv-user")).thenReturn("alice");
        Account localAccount = account("alice", AccountType.LOCAL, AccountRole.ADMIN);
        when(accountRepo.findByUsernameAndActiveTrue("alice")).thenReturn(Optional.of(localAccount));

        assertThatThrownBy(() -> service.authenticate(request))
                .isInstanceOf(BadCredentialsException.class);
    }

    // ── Happy path ──────────────────────────────────────────────────────────

    @Test
    void issues_jwt_and_updates_last_login_when_trust_and_account_both_match() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings(EnumSet.of(AccountType.WEBSEAL), "10.0.0.0/8")));
        when(request.getRemoteAddr()).thenReturn("10.1.2.3");
        when(request.getHeader("iv-user")).thenReturn("alice");
        Account account = account("alice", AccountType.WEBSEAL, AccountRole.ADMIN);
        when(accountRepo.findByUsernameAndActiveTrue("alice")).thenReturn(Optional.of(account));

        Optional<WebSealAuthenticationService.WebSealLoginResult> out = service.authenticate(request);

        assertThat(out).isPresent();
        assertThat(out.get().token()).isEqualTo("jwt-token");
        assertThat(out.get().username()).isEqualTo("alice");
        assertThat(out.get().accountType()).isEqualTo("ADMIN");
        assertThat(account.getLastLoginAt()).isNotNull();
    }

    @Test
    void custom_header_names_are_respected() {
        ApplicationSettings s = settings(EnumSet.of(AccountType.WEBSEAL), "10.0.0.0/8");
        s.setWebsealUserHeader("X-IV-User");
        s.setWebsealGroupsHeader("X-IV-Groups");
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(s));
        when(request.getRemoteAddr()).thenReturn("10.1.2.3");
        when(request.getHeader("X-IV-User")).thenReturn("alice");
        Account account = account("alice", AccountType.WEBSEAL, AccountRole.ADMIN);
        when(accountRepo.findByUsernameAndActiveTrue("alice")).thenReturn(Optional.of(account));

        assertThat(service.authenticate(request)).isPresent();
    }

    @Test
    void superadmin_account_yields_superadmin_principal_type() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings(EnumSet.of(AccountType.WEBSEAL), "10.0.0.0/8")));
        when(request.getRemoteAddr()).thenReturn("10.1.2.3");
        when(request.getHeader("iv-user")).thenReturn("root");
        when(accountRepo.findByUsernameAndActiveTrue("root"))
                .thenReturn(Optional.of(account("root", AccountType.WEBSEAL, AccountRole.SUPERADMIN)));

        Optional<WebSealAuthenticationService.WebSealLoginResult> out = service.authenticate(request);
        assertThat(out).isPresent();
        assertThat(out.get().accountType()).isEqualTo("SUPERADMIN");
    }

    // ── Group parsing (audit only, never used for authorization) ────────────

    @Test
    void plain_comma_groups_parsed() {
        assertThat(WebSealAuthenticationService.parseGroups("groupA,groupB, groupC"))
                .containsExactly("groupA", "groupB", "groupC");
    }

    @Test
    void quoted_groups_parsed_preserving_embedded_commas() {
        List<String> g = WebSealAuthenticationService.parseGroups(
                "\"cn=admins,ou=g,dc=corp\",\"cn=users,ou=g,dc=corp\"");
        assertThat(g).containsExactly("cn=admins,ou=g,dc=corp", "cn=users,ou=g,dc=corp");
    }

    @Test
    void empty_or_null_group_input_yields_empty_list() {
        assertThat(WebSealAuthenticationService.parseGroups(null)).isEmpty();
        assertThat(WebSealAuthenticationService.parseGroups("")).isEmpty();
        assertThat(WebSealAuthenticationService.parseGroups("   ")).isEmpty();
    }

    // ── Logout URL ──────────────────────────────────────────────────────────

    @Test
    void logoutUrlFor_returns_configured_url_for_WEBSEAL_principal() {
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(settings(EnumSet.of(AccountType.WEBSEAL), "10.0.0.0/8")));
        assertThat(service.logoutUrlFor(AccountType.WEBSEAL)).contains("/pkmslogout");
    }

    @Test
    void logoutUrlFor_returns_empty_for_non_webseal_principal() {
        assertThat(service.logoutUrlFor(AccountType.OIDC)).isEmpty();
        assertThat(service.logoutUrlFor(AccountType.LOCAL)).isEmpty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ApplicationSettings settings(Set<AccountType> enabled, String proxies) {
        ApplicationSettings s = new ApplicationSettings();
        s.setEnabledAuthTypes(enabled);
        s.setWebsealTrustedProxies(proxies);
        s.setWebsealUserHeader("iv-user");
        s.setWebsealGroupsHeader("iv-groups");
        s.setWebsealLogoutUrl("/pkmslogout");
        return s;
    }

    private Account account(String username, AccountType type, AccountRole role) {
        Account a = new Account();
        a.setId(UUID.randomUUID());
        a.setUsername(username);
        a.setAuthType(type);
        a.setRole(role);
        a.setActive(true);
        return a;
    }
}
