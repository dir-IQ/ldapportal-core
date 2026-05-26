// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.auth.dto.LoginRequest;
import com.ldapportal.auth.dto.LoginResponse;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.ApplicationSettingsRepository;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock private AccountRepository             accountRepo;
    @Mock private ApplicationSettingsRepository settingsRepo;
    @Mock private JwtTokenService               jwtTokenService;
    @Mock private PasswordEncoder               passwordEncoder;
    @Mock private EncryptionService             encryptionService;

    private AuthenticationService authService;

    private InMemoryDirectoryServer ldapServer;

    private final UUID accountId = UUID.randomUUID();

    // LDAP credential registered with the in-memory server
    private static final String LDAP_USER     = "alice";
    private static final String LDAP_BIND_DN  = "uid=alice,dc=example,dc=com";
    private static final String LDAP_PASSWORD = "ldap-secret";

    @BeforeEach
    void setUp() throws Exception {
        authService = new AuthenticationService(
                accountRepo, settingsRepo, jwtTokenService, passwordEncoder, encryptionService);

        InMemoryDirectoryServerConfig cfg =
                new InMemoryDirectoryServerConfig("dc=example,dc=com");
        cfg.addAdditionalBindCredentials(LDAP_BIND_DN, LDAP_PASSWORD);
        ldapServer = new InMemoryDirectoryServer(cfg);
        ldapServer.startListening();

        lenient().when(jwtTokenService.issue(any())).thenReturn("token-abc");
    }

    @AfterEach
    void tearDown() {
        if (ldapServer != null) ldapServer.shutDown(true);
    }

    // ── LOCAL superadmin ──────────────────────────────────────────────────────

    @Test
    void login_localSuperadmin_correctPassword_returnsJwt() {
        Account sa = localAccount("alice", AccountRole.SUPERADMIN, "$2a$bcrypt");
        when(accountRepo.findByUsernameAndActiveTrue("alice")).thenReturn(Optional.of(sa));
        when(passwordEncoder.matches("secret", "$2a$bcrypt")).thenReturn(true);

        LoginResponse resp = authService.login(new LoginRequest("alice", "secret"));

        assertThat(resp.token()).isEqualTo("token-abc");
        assertThat(resp.username()).isEqualTo("alice");
        assertThat(resp.accountType()).isEqualTo("SUPERADMIN");
        assertThat(sa.getLastLoginAt()).isNotNull();
    }

    @Test
    void login_localAdmin_correctPassword_returnsJwt() {
        Account admin = localAccount("bob", AccountRole.ADMIN, "$2a$hash");
        when(accountRepo.findByUsernameAndActiveTrue("bob")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("pass", "$2a$hash")).thenReturn(true);

        LoginResponse resp = authService.login(new LoginRequest("bob", "pass"));

        assertThat(resp.accountType()).isEqualTo("ADMIN");
        assertThat(resp.username()).isEqualTo("bob");
    }

    @Test
    void login_localAccount_wrongPassword_throwsBadCredentials() {
        Account sa = localAccount("alice", AccountRole.SUPERADMIN, "$2a$bcrypt");
        when(accountRepo.findByUsernameAndActiveTrue("alice")).thenReturn(Optional.of(sa));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_localAccount_notFound_throwsBadCredentials() {
        when(accountRepo.findByUsernameAndActiveTrue("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("unknown", "pw")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_localAccount_setsLastLoginAt() {
        Account account = localAccount("alice", AccountRole.ADMIN, "$2a$hash");
        when(accountRepo.findByUsernameAndActiveTrue("alice")).thenReturn(Optional.of(account));
        when(passwordEncoder.matches("pass", "$2a$hash")).thenReturn(true);

        assertThat(account.getLastLoginAt()).isNull();
        authService.login(new LoginRequest("alice", "pass"));
        assertThat(account.getLastLoginAt()).isNotNull();
    }

    // ── LDAP account ──────────────────────────────────────────────────────────

    @Test
    void login_ldapAccount_correctPassword_returnsJwt() {
        Account account = ldapAccount(LDAP_USER, AccountRole.ADMIN);
        when(accountRepo.findByUsernameAndActiveTrue(LDAP_USER)).thenReturn(Optional.of(account));
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(ldapSettings(
                "uid={username},dc=example,dc=com")));

        LoginResponse resp = authService.login(new LoginRequest(LDAP_USER, LDAP_PASSWORD));

        assertThat(resp.token()).isEqualTo("token-abc");
        assertThat(resp.accountType()).isEqualTo("ADMIN");
    }

    @Test
    void login_ldapAccount_wrongPassword_throwsBadCredentials() {
        Account account = ldapAccount(LDAP_USER, AccountRole.ADMIN);
        when(accountRepo.findByUsernameAndActiveTrue(LDAP_USER)).thenReturn(Optional.of(account));
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(ldapSettings(
                "uid={username},dc=example,dc=com")));

        assertThatThrownBy(() -> authService.login(new LoginRequest(LDAP_USER, "wrong-pw")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_ldapAccount_noSettings_throwsBadCredentials() {
        Account account = ldapAccount(LDAP_USER, AccountRole.ADMIN);
        when(accountRepo.findByUsernameAndActiveTrue(LDAP_USER)).thenReturn(Optional.of(account));
        when(settingsRepo.findFirstBy()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest(LDAP_USER, LDAP_PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_ldapAccount_noBindDnPattern_throwsBadCredentials() {
        Account account = ldapAccount(LDAP_USER, AccountRole.ADMIN);
        when(accountRepo.findByUsernameAndActiveTrue(LDAP_USER)).thenReturn(Optional.of(account));
        when(settingsRepo.findFirstBy()).thenReturn(Optional.of(ldapSettings(null)));

        assertThatThrownBy(() -> authService.login(new LoginRequest(LDAP_USER, LDAP_PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Account localAccount(String username, AccountRole role, String hash) {
        Account a = new Account();
        a.setId(accountId);
        a.setUsername(username);
        a.setRole(role);
        a.setAuthType(AccountType.LOCAL);
        a.setPasswordHash(hash);
        a.setActive(true);
        return a;
    }

    private Account ldapAccount(String username, AccountRole role) {
        Account a = new Account();
        a.setId(accountId);
        a.setUsername(username);
        a.setRole(role);
        a.setAuthType(AccountType.LDAP);
        a.setActive(true);
        return a;
    }

    private ApplicationSettings ldapSettings(String bindDnPattern) {
        ApplicationSettings s = new ApplicationSettings();
        s.getEnabledAuthTypes().add(AccountType.LDAP);
        s.setLdapAuthHost("localhost");
        s.setLdapAuthPort(ldapServer.getListenPort());
        s.setLdapAuthSslMode(SslMode.NONE);
        s.setLdapAuthBindDnPattern(bindDnPattern);
        return s;
    }
}
