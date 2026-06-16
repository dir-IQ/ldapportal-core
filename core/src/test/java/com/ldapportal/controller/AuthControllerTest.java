// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.AuthenticationService;
import com.ldapportal.auth.LoginRateLimiter;
import com.ldapportal.auth.OidcAuthenticationService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.auth.WebSealAuthenticationService;
import com.ldapportal.auth.dto.LoginRequest;
import com.ldapportal.auth.dto.LoginResponse;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.ApplicationSettings;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapUser;
import com.ldapportal.repository.AdminProfileRoleRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import com.ldapportal.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
class AuthControllerTest extends BaseControllerTest {

    @Autowired MockMvc       mockMvc;
    @Autowired ObjectMapper  objectMapper;

    @MockitoBean AuthenticationService      authenticationService;
    @MockitoBean OidcAuthenticationService oidcAuthenticationService;
    @MockitoBean WebSealAuthenticationService webSealAuthenticationService;
    @MockitoBean LoginRateLimiter          loginRateLimiter;
    @MockitoBean AdminProfileRoleRepository adminProfileRoleRepository;
    @MockitoBean ProvisioningProfileRepository provisioningProfileRepository;
    @MockitoBean DirectoryConnectionRepository directoryConnectionRepository;
    // accountRepository inherited from BaseControllerTest (also wired into JwtAuthenticationFilter).
    @MockitoBean com.ldapportal.repository.AdminFeaturePermissionRepository featurePermRepo;
    @MockitoBean org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @MockitoBean LdapConnectionFactory ldapConnectionFactory;
    @MockitoBean LdapUserService ldapUserService;
    @MockitoBean ApplicationSettingsService applicationSettingsService;
    @MockitoBean com.ldapportal.core.entitlement.EntitlementService entitlementService;
    @MockitoBean com.ldapportal.service.AuditService auditService;
    @MockitoBean com.ldapportal.service.UserPreferencesService userPreferencesService;
    @MockitoBean com.ldapportal.service.SuperadminPermissionService superadminPermissionService;

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void login_validCredentials_returns200WithToken() throws Exception {
        LoginRequest req  = new LoginRequest("admin", "secret");
        LoginResponse res = new LoginResponse("jwt-token", "admin", "SUPERADMIN", null);
        given(authenticationService.login(any())).willReturn(res);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.accountType").value("SUPERADMIN"));
    }

    @Test
    void login_badCredentials_returns401() throws Exception {
        LoginRequest req = new LoginRequest("admin", "wrong");
        given(authenticationService.login(any())).willThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_authenticated_returnsUsernameAndType() throws Exception {
        AuthPrincipal principal = new AuthPrincipal(PrincipalType.SUPERADMIN, ACCOUNT_ID, "alice");
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));

        mockMvc.perform(get("/api/v1/auth/me").with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.accountType").value("SUPERADMIN"))
                .andExpect(jsonPath("$.id").value(ACCOUNT_ID.toString()));
    }

    @Test
    void me_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setupStatus_unauthenticated_returnsFalseByDefault() throws Exception {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSetupCompleted(false);
        given(applicationSettingsService.getEntity()).willReturn(settings);

        mockMvc.perform(get("/api/v1/auth/setup-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupCompleted").value(false));
    }

    @Test
    void setupStatus_afterSetup_returnsTrue() throws Exception {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSetupCompleted(true);
        given(applicationSettingsService.getEntity()).willReturn(settings);

        mockMvc.perform(get("/api/v1/auth/setup-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupCompleted").value(true));
    }

    /**
     * Regression: when the persisted flag is {@code false} but at least one
     * directory connection exists, setup-status returns {@code true} and
     * lazily heals the flag in the DB. Closes the bug where deleting one
     * of two directories could re-trap a superadmin in the wizard.
     */
    @Test
    void setupStatus_flagFalseButDirectoriesExist_selfHealsAndReturnsTrue() throws Exception {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSetupCompleted(false);
        given(applicationSettingsService.getEntity()).willReturn(settings);
        given(directoryConnectionRepository.count()).willReturn(2L);

        mockMvc.perform(get("/api/v1/auth/setup-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupCompleted").value(true));

        org.mockito.Mockito.verify(applicationSettingsService).markSetupComplete();
    }

    /**
     * The self-heal must NOT touch the flag when it's already {@code true}.
     * Avoids spurious DB writes on every navigation in the steady state.
     */
    @Test
    void setupStatus_flagTrueAndDirectoriesExist_returnsTrueWithoutWriting() throws Exception {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSetupCompleted(true);
        given(applicationSettingsService.getEntity()).willReturn(settings);
        given(directoryConnectionRepository.count()).willReturn(3L);

        mockMvc.perform(get("/api/v1/auth/setup-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupCompleted").value(true));

        org.mockito.Mockito.verify(applicationSettingsService, org.mockito.Mockito.never())
                .markSetupComplete();
    }

    /**
     * Fresh-install case: flag is false AND no directories. The wizard
     * should still be required — self-heal must NOT run.
     */
    @Test
    void setupStatus_flagFalseAndNoDirectories_returnsFalseWithoutWriting() throws Exception {
        ApplicationSettings settings = new ApplicationSettings();
        settings.setSetupCompleted(false);
        given(applicationSettingsService.getEntity()).willReturn(settings);
        given(directoryConnectionRepository.count()).willReturn(0L);

        mockMvc.perform(get("/api/v1/auth/setup-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupCompleted").value(false));

        org.mockito.Mockito.verify(applicationSettingsService, org.mockito.Mockito.never())
                .markSetupComplete();
    }

    // ── Account profile (display name / email) ────────────────────────────────
    // UI customizations (theme, density, ...) moved to PreferencesController and
    // are covered by its own tests — this endpoint now only touches the account
    // profile fields.

    /**
     * Helper that wires up a mocked Account for the profile round-trip tests.
     * The controller calls `accountRepo.save(...)`; we capture the arg via
     * ArgumentCaptor to inspect the saved state.
     */
    private Account givenAuthenticatedAccount() {
        Account acct = new Account();
        acct.setId(ACCOUNT_ID);
        given(accountRepository.findById(ACCOUNT_ID)).willReturn(java.util.Optional.of(acct));
        given(accountRepository.save(any(Account.class)))
                .willAnswer(inv -> inv.getArgument(0));
        return acct;
    }

    /**
     * Local auth-token builder that uses ACCOUNT_ID (the base class's
     * superadminAuth() generates a fresh random UUID per call, which can't
     * be matched by an accountRepository.findById(ACCOUNT_ID) stub).
     */
    private UsernamePasswordAuthenticationToken authForAccount() {
        AuthPrincipal p = new AuthPrincipal(PrincipalType.SUPERADMIN, ACCOUNT_ID, "alice");
        return new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
    }

    @Test
    void updatePreferences_profileFields_returns200AndPersists() throws Exception {
        givenAuthenticatedAccount();

        mockMvc.perform(post("/api/v1/auth/me/preferences")
                        .with(authentication(authForAccount()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alice\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        org.mockito.ArgumentCaptor<Account> captor =
                org.mockito.ArgumentCaptor.forClass(Account.class);
        org.mockito.Mockito.verify(accountRepository).save(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "Alice", captor.getValue().getDisplayName());
        org.junit.jupiter.api.Assertions.assertEquals(
                "alice@example.com", captor.getValue().getEmail());
    }

    @Test
    void updatePreferences_nullFields_leaveAccountUnchanged() throws Exception {
        // Partial-update contract: caller sends only displayName; email is
        // left untouched.
        Account acct = givenAuthenticatedAccount();
        acct.setEmail("original@example.com");

        mockMvc.perform(post("/api/v1/auth/me/preferences")
                        .with(authentication(authForAccount()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alice\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Account> captor =
                org.mockito.ArgumentCaptor.forClass(Account.class);
        org.mockito.Mockito.verify(accountRepository).save(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "Alice", captor.getValue().getDisplayName());
        org.junit.jupiter.api.Assertions.assertEquals(
                "original@example.com", captor.getValue().getEmail());
    }

    // ── Self-service login: system errors must not read as bad credentials ────

    private DirectoryConnection selfServiceDir(UUID dirId) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(dirId);
        dc.setDisplayName("OUD1");
        dc.setBaseDn("dc=example,dc=com");
        dc.setSelfServiceEnabled(true);
        dc.setSelfServiceLoginAttribute("uid");
        return dc;
    }

    /** A bind/connection failure for a non-credential reason (server down,
     *  timeout, TLS) must surface as a 5xx, not a 401 that blames the user's
     *  password. The user was found, so we're past the credential lookup. */
    @Test
    void selfServiceLogin_ldapUnreachable_returns502NotBadCredentials() throws Exception {
        UUID dirId = UUID.randomUUID();
        given(directoryConnectionRepository.findById(dirId))
                .willReturn(Optional.of(selfServiceDir(dirId)));
        given(ldapUserService.searchUsers(any(), any(), any(), anyInt(), any()))
                .willReturn(List.of(new LdapUser("uid=jane,dc=example,dc=com", Map.of())));
        // The bind connection can't be opened (server unreachable). This is the
        // catch that previously mapped every failure to 401 "bad credentials".
        given(ldapConnectionFactory.openUnboundConnection(any()))
                .willThrow(new RuntimeException("connection refused"));

        var req = new AuthController.SelfServiceLoginRequest(dirId, "jane", "secret");
        mockMvc.perform(post("/api/v1/auth/self-service/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadGateway());
    }

    /** A genuine authentication failure (here: no matching user) still returns
     *  401 with the generic message. */
    @Test
    void selfServiceLogin_authFailure_returns401() throws Exception {
        UUID dirId = UUID.randomUUID();
        given(directoryConnectionRepository.findById(dirId))
                .willReturn(Optional.of(selfServiceDir(dirId)));
        given(ldapUserService.searchUsers(any(), any(), any(), anyInt(), any()))
                .willReturn(List.of());

        var req = new AuthController.SelfServiceLoginRequest(dirId, "ghost", "secret");
        mockMvc.perform(post("/api/v1/auth/self-service/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }
}
