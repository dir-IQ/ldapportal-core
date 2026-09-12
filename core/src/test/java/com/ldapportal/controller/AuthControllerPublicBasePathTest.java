// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.AuthenticationService;
import com.ldapportal.auth.LoginRateLimiter;
import com.ldapportal.auth.OidcAuthenticationService;
import com.ldapportal.auth.WebSealAuthenticationService;
import com.ldapportal.auth.dto.LoginRequest;
import com.ldapportal.auth.dto.LoginResponse;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * When the app is exposed under a URL path prefix by a proxy that does not
 * rewrite cookie paths, the session and preference cookies must carry that
 * prefix in their {@code Path}, or the browser never returns them.
 */
@WebMvcTest(AuthController.class)
@TestPropertySource(properties = "app.public-base-path=/idm/")
class AuthControllerPublicBasePathTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

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
    void loginCookiesCarryThePublicBasePath() throws Exception {
        given(authenticationService.login(any()))
                .willReturn(new LoginResponse("jwt-token", "admin", "SUPERADMIN", null));

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("admin", "secret"))))
                .andExpect(status().isOk())
                .andReturn();

        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).anySatisfy(c -> {
            assertThat(c).startsWith("jwt=");
            assertThat(c).contains("Path=/idm/api/v1");
        });
    }

    @Test
    void logoutClearsTheCookieOnTheSamePrefixedPath() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andReturn();

        List<String> cookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies).anySatisfy(c -> {
            assertThat(c).startsWith("jwt=");
            assertThat(c).contains("Path=/idm/api/v1");
            assertThat(c).contains("Max-Age=0");
        });
        assertThat(cookies).anySatisfy(c -> assertThat(c).contains("Path=/idm/;"));
    }
}
