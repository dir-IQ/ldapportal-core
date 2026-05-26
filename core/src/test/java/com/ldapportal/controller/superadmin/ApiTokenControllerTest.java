// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.ApiTokenAuthenticationDetails;
import com.ldapportal.auth.ApiTokenService;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.dto.apitoken.CreateApiTokenRequest;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.ApiToken;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ApiTokenController.class)
class ApiTokenControllerTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // accountRepository inherited from BaseControllerTest (also resolves the
    // caller Account in the controller and backs JwtAuthenticationFilter).

    private Account creator;

    @BeforeEach
    void setUp() {
        creator = new Account();
        creator.setId(UUID.randomUUID());
        creator.setUsername("alice");
        creator.setRole(AccountRole.SUPERADMIN);
        creator.setActive(true);
        when(accountRepository.findById(creator.getId())).thenReturn(Optional.of(creator));
    }

    // Shadows BaseControllerTest.superadminAuth() so the principal id matches
    // creator.getId() — required for accountRepository.findById stubs to fire.
    // (The base method is static; this instance method takes precedence for
    // unqualified calls within this class.)
    protected UsernamePasswordAuthenticationToken superadminAuth() {
        AuthPrincipal p = new AuthPrincipal(
                PrincipalType.SUPERADMIN, creator.getId(), "alice");
        return new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
    }

    private UsernamePasswordAuthenticationToken apiTokenAuth() {
        AuthPrincipal p = new AuthPrincipal(
                PrincipalType.SUPERADMIN, creator.getId(), "alice");
        var auth = new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
        auth.setDetails(new ApiTokenAuthenticationDetails(UUID.randomUUID(), "ci"));
        return auth;
    }

    private ApiToken newStored() {
        ApiToken t = new ApiToken();
        t.setId(UUID.randomUUID());
        t.setName("ci-terraform");
        t.setTokenPrefix("ldap_pat_aB3xQ9z");
        t.setCreatedBy(creator);
        t.setCreatedAt(Instant.now());
        t.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        return t;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Test
    void create_asSuperadmin_returns201WithPlaintext() throws Exception {
        ApiToken stored = newStored();
        when(apiTokenService.create(eq("ci-terraform"), any(), any(Instant.class), eq(creator)))
                .thenReturn(new ApiTokenService.CreateResult(stored, "ldap_pat_newpt"));
        CreateApiTokenRequest req = new CreateApiTokenRequest(
                "ci-terraform", null, Instant.now().plus(30, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/v1/superadmin/api-tokens")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plaintext").value("ldap_pat_newpt"))
                .andExpect(jsonPath("$.token.name").value("ci-terraform"));
    }

    @Test
    void create_missingName_returns400() throws Exception {
        String body = """
                {"description":null,"expiresAt":"2099-01-01T00:00:00Z"}
                """;
        mockMvc.perform(post("/api/v1/superadmin/api-tokens")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_pastExpiry_returns400() throws Exception {
        String body = String.format("""
                {"name":"x","description":null,"expiresAt":"%s"}
                """, Instant.now().minus(1, ChronoUnit.DAYS));
        mockMvc.perform(post("/api/v1/superadmin/api-tokens")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_asApiTokenCaller_returns403() throws Exception {
        CreateApiTokenRequest req = new CreateApiTokenRequest(
                "x", null, Instant.now().plus(30, ChronoUnit.DAYS));
        mockMvc.perform(post("/api/v1/superadmin/api-tokens")
                        .with(authentication(apiTokenAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_beyondMaxWindow_returns400() throws Exception {
        when(apiTokenService.create(any(), any(), any(Instant.class), any(Account.class)))
                .thenThrow(new IllegalArgumentException(
                        "API token expiresAt cannot exceed 2 years from now"));
        CreateApiTokenRequest req = new CreateApiTokenRequest(
                "x", null, Instant.now().plus(30, ChronoUnit.DAYS));

        mockMvc.perform(post("/api/v1/superadmin/api-tokens")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Test
    void list_asSuperadmin_returnsActiveTokens() throws Exception {
        when(apiTokenService.list(false)).thenReturn(List.of(newStored()));
        mockMvc.perform(get("/api/v1/superadmin/api-tokens")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("ci-terraform"));
    }

    @Test
    void list_includeRevoked_callsServiceWithTrue() throws Exception {
        when(apiTokenService.list(true)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/superadmin/api-tokens?includeRevoked=true")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk());
        verify(apiTokenService).list(true);
    }

    // ── Get ───────────────────────────────────────────────────────────────────

    @Test
    void get_existing_returns200() throws Exception {
        ApiToken stored = newStored();
        when(apiTokenService.get(stored.getId())).thenReturn(stored);
        mockMvc.perform(get("/api/v1/superadmin/api-tokens/" + stored.getId())
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(stored.getId().toString()))
                .andExpect(jsonPath("$.name").value("ci-terraform"));
    }

    @Test
    void get_unknown_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(apiTokenService.get(id))
                .thenThrow(new ResourceNotFoundException("ApiToken", id));
        mockMvc.perform(get("/api/v1/superadmin/api-tokens/" + id)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    // ── Rotate ────────────────────────────────────────────────────────────────

    @Test
    void rotate_asSuperadmin_returnsNewPlaintext() throws Exception {
        ApiToken stored = newStored();
        when(apiTokenService.rotate(eq(stored.getId()), any(AuthPrincipal.class)))
                .thenReturn(new ApiTokenService.CreateResult(stored, "ldap_pat_rotated"));
        mockMvc.perform(post("/api/v1/superadmin/api-tokens/" + stored.getId() + "/rotate")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plaintext").value("ldap_pat_rotated"));
    }

    @Test
    void rotate_revoked_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(apiTokenService.rotate(eq(id), any(AuthPrincipal.class)))
                .thenThrow(new ConflictException("revoked"));
        mockMvc.perform(post("/api/v1/superadmin/api-tokens/" + id + "/rotate")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isConflict());
    }

    @Test
    void rotate_asApiTokenCaller_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/superadmin/api-tokens/" + UUID.randomUUID() + "/rotate")
                        .with(authentication(apiTokenAuth())))
                .andExpect(status().isForbidden());
    }

    // ── Revoke ────────────────────────────────────────────────────────────────

    @Test
    void revoke_asSuperadmin_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/superadmin/api-tokens/" + id)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isNoContent());
        verify(apiTokenService).revoke(eq(id), any(AuthPrincipal.class));
    }

    @Test
    void revoke_unknown_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("ApiToken", id))
                .when(apiTokenService).revoke(eq(id), any(AuthPrincipal.class));
        mockMvc.perform(delete("/api/v1/superadmin/api-tokens/" + id)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void revoke_asApiTokenCaller_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/superadmin/api-tokens/" + UUID.randomUUID())
                        .with(authentication(apiTokenAuth())))
                .andExpect(status().isForbidden());
    }
}
