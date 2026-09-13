// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.AuthenticationService;
import com.ldapportal.auth.LoginRateLimiter;
import com.ldapportal.auth.OidcAuthenticationService;
import com.ldapportal.auth.WebSealAuthenticationService;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.repository.AdminProfileRoleRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import com.ldapportal.service.ApplicationSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CORS allow-list as exercised by a browser behind a reverse proxy or
 * WebSEAL junction. Browsers attach {@code Origin} to every POST — same-origin
 * included — and Spring compares it with the scheme/host/port the backend
 * itself sees, so behind a proxy a same-origin Logout POST is evaluated
 * against {@code CORS_ALLOWED_ORIGIN}. A dual-frontend deployment therefore
 * needs both public origins listed; anything else must be a clear 403.
 */
@WebMvcTest(AuthController.class)
@TestPropertySource(properties = "app.cors.allowed-origins=https://lidm.example.com, https://sa.example.com")
class AuthControllerCorsTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AuthenticationService authenticationService;
    @MockitoBean OidcAuthenticationService oidcAuthenticationService;
    @MockitoBean WebSealAuthenticationService webSealAuthenticationService;
    @MockitoBean LoginRateLimiter loginRateLimiter;
    @MockitoBean AdminProfileRoleRepository adminProfileRoleRepository;
    @MockitoBean ProvisioningProfileRepository provisioningProfileRepository;
    @MockitoBean DirectoryConnectionRepository directoryConnectionRepository;
    @MockitoBean com.ldapportal.repository.AdminFeaturePermissionRepository featurePermRepo;
    @MockitoBean org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @MockitoBean LdapConnectionFactory ldapConnectionFactory;
    @MockitoBean LdapUserService ldapUserService;
    @MockitoBean ApplicationSettingsService applicationSettingsService;
    @MockitoBean com.ldapportal.core.entitlement.EntitlementService entitlementService;
    @MockitoBean com.ldapportal.service.AuditService auditService;
    @MockitoBean com.ldapportal.service.UserPreferencesService userPreferencesService;
    @MockitoBean com.ldapportal.service.SuperadminPermissionService superadminPermissionService;

    @Test
    void logout_fromFirstListedOrigin_isAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.ORIGIN, "https://lidm.example.com"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://lidm.example.com"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    /** The comma-separated list is honoured (and surrounding whitespace ignored). */
    @Test
    void logout_fromSecondListedOrigin_isAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.ORIGIN, "https://sa.example.com"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://sa.example.com"));
    }

    @Test
    void logout_fromUnlistedOrigin_isRejectedWith403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.ORIGIN, "https://other.example.com"))
                .andExpect(status().isForbidden());
    }

    /** Requests without Origin (curl, same-origin GETs) are untouched by the policy. */
    @Test
    void logout_withoutOriginHeader_isAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk());
    }

    /** PATCH is used by the SPA (preferences, report toggles) and must be allowed. */
    @Test
    void preflight_forPatch_isAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/auth/me/preferences")
                        .header(HttpHeaders.ORIGIN, "https://lidm.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, org.hamcrest.Matchers.containsString("PATCH")));
    }

    @Test
    void preflight_forUnlistedMethod_isRejected() throws Exception {
        mockMvc.perform(options("/api/v1/auth/logout")
                        .header(HttpHeaders.ORIGIN, "https://lidm.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "TRACE"))
                .andExpect(status().isForbidden());
    }
}
