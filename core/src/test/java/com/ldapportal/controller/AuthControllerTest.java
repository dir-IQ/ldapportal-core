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
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.LdapUserService;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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

    // ── Preferences (density) ─────────────────────────────────────────────────

    /**
     * Helper that wires up a mocked Account for the preferences round-trip
     * tests. The controller calls `accountRepo.save(...)`; we capture the
     * arg via ArgumentCaptor to inspect the saved state.
     */
    private Account givenAuthenticatedAccountWithDensity(String initialDensity) {
        Account acct = new Account();
        acct.setId(ACCOUNT_ID);
        acct.setDensityPreference(initialDensity);
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
    void updatePreferences_validDensity_returns200AndPersists() throws Exception {
        givenAuthenticatedAccountWithDensity("comfortable");

        mockMvc.perform(post("/api/v1/auth/me/preferences")
                        .with(authentication(authForAccount()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"densityPreference\":\"compact\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        org.mockito.ArgumentCaptor<Account> captor =
                org.mockito.ArgumentCaptor.forClass(Account.class);
        org.mockito.Mockito.verify(accountRepository).save(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "compact", captor.getValue().getDensityPreference());
    }

    @Test
    void updatePreferences_invalidDensity_returns400() throws Exception {
        givenAuthenticatedAccountWithDensity("comfortable");

        mockMvc.perform(post("/api/v1/auth/me/preferences")
                        .with(authentication(authForAccount()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"densityPreference\":\"huge\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("Invalid density")));

        // Save must NOT be called for a rejected request.
        org.mockito.Mockito.verify(accountRepository, org.mockito.Mockito.never())
                .save(any(Account.class));
    }

    @Test
    void updatePreferences_nullDensity_leavesAccountUnchanged() throws Exception {
        // Partial-update contract: caller sends only displayName; density
        // should NOT change. Mirrors how theme/email are handled today.
        givenAuthenticatedAccountWithDensity("compact");

        mockMvc.perform(post("/api/v1/auth/me/preferences")
                        .with(authentication(authForAccount()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alice\"}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<Account> captor =
                org.mockito.ArgumentCaptor.forClass(Account.class);
        org.mockito.Mockito.verify(accountRepository).save(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                "compact", captor.getValue().getDensityPreference());
        org.junit.jupiter.api.Assertions.assertEquals(
                "Alice", captor.getValue().getDisplayName());
    }
}
