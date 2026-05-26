// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.ApiTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test — exercises the full filter chain against a real
 * DB and the real Spring Security configuration. One integration test in
 * addition to the unit coverage in {@link ApiTokenServiceTest},
 * {@link ApiTokenAuthenticationFilterTest}, and
 * {@link com.ldapportal.controller.superadmin.ApiTokenControllerTest}.
 *
 * <p>Note: no {@code @Transactional} here — requests processed by MockMvc
 * open their own transactions and cannot see uncommitted rows from an outer
 * test-level transaction. The {@link #setUp()} method handles isolation via
 * {@code apiTokenRepository.deleteAll()} instead.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiTokenEndToEndTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountRepository accountRepository;
    @Autowired private ApiTokenRepository apiTokenRepository;
    @Autowired private ApiTokenService apiTokenService;
    @Autowired private JwtTokenService jwtTokenService;

    private Account superadmin;

    @BeforeEach
    void setUp() {
        apiTokenRepository.deleteAll();
        superadmin = accountRepository.findAll().stream()
                .filter(a -> a.getRole() == AccountRole.SUPERADMIN && a.isActive())
                .findFirst()
                .orElseGet(this::insertSuperadmin);
    }

    private Account insertSuperadmin() {
        Account a = new Account();
        a.setUsername("e2e-superadmin-" + UUID.randomUUID());
        a.setDisplayName("E2E Superadmin");
        a.setRole(AccountRole.SUPERADMIN);
        a.setAuthType(AccountType.LOCAL);
        a.setPasswordHash("$2a$10$irrelevant");
        a.setActive(true);
        return accountRepository.save(a);
    }

    private UsernamePasswordAuthenticationToken superadminAuth() {
        AuthPrincipal p = new AuthPrincipal(
                PrincipalType.SUPERADMIN, superadmin.getId(), superadmin.getUsername());
        return new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
    }

    @Test
    void freshlyCreatedTokenAuthenticatesAgainstProtectedEndpoint() throws Exception {
        String plaintext = createTokenAndReturnPlaintext(30);

        // Hit a protected endpoint with the bearer.
        mockMvc.perform(get("/api/v1/superadmin/api-tokens")
                        .header("Authorization", "Bearer " + plaintext))
                .andExpect(status().isOk());
    }

    @Test
    void revokedTokenRejected() throws Exception {
        String plaintext = createTokenAndReturnPlaintext(30);
        // Revoke all tokens this test just created
        apiTokenRepository.findAll().forEach(t -> {
            t.setRevokedAt(Instant.now());
            apiTokenRepository.save(t);
        });

        mockMvc.perform(get("/api/v1/superadmin/api-tokens")
                        .header("Authorization", "Bearer " + plaintext))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedPrefixedTokenRejected() throws Exception {
        mockMvc.perform(get("/api/v1/superadmin/api-tokens")
                        .header("Authorization", "Bearer ldap_pat_garbagexxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenCannotCreateAnotherToken() throws Exception {
        String plaintext = createTokenAndReturnPlaintext(30);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "would-be-child");
        body.put("expiresAt", Instant.now().plus(30, ChronoUnit.DAYS).toString());

        mockMvc.perform(post("/api/v1/superadmin/api-tokens")
                        .header("Authorization", "Bearer " + plaintext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void jwtBearer_stillWorks() throws Exception {
        // Control case: the new filter must not break the existing JWT path.
        AuthPrincipal p = new AuthPrincipal(
                PrincipalType.SUPERADMIN, superadmin.getId(), superadmin.getUsername());
        String jwt = jwtTokenService.issue(p);

        mockMvc.perform(get("/api/v1/superadmin/api-tokens")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk());
    }

    @Test
    void expiredTokenRejected() throws Exception {
        String plaintext = createTokenAndReturnPlaintext(30);
        // Set expiry to the past; authentication should reject.
        apiTokenRepository.findAll().forEach(t -> {
            t.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
            apiTokenRepository.save(t);
        });

        mockMvc.perform(get("/api/v1/superadmin/api-tokens")
                        .header("Authorization", "Bearer " + plaintext))
                .andExpect(status().isUnauthorized());
    }

    private String createTokenAndReturnPlaintext(int days) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", "e2e-" + UUID.randomUUID());
        body.put("expiresAt", Instant.now().plus(days, ChronoUnit.DAYS).toString());

        MvcResult result = mockMvc.perform(post("/api/v1/superadmin/api-tokens")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();

        String resp = result.getResponse().getContentAsString();
        String plaintext = objectMapper.readTree(resp).get("plaintext").asText();
        assertThat(plaintext).startsWith("ldap_pat_");
        return plaintext;
    }
}
