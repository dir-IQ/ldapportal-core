// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.repository.AccountRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JwtAuthenticationFilter}. Focus: the guard that
 * protects a pre-existing authentication (set by {@link ApiTokenAuthenticationFilter}
 * upstream) from being cleared when the JWT filter encounters an
 * {@code ldap_pat_} bearer that it can't parse as a JWT.
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtTokenService jwtTokenService;
    @Mock private AccountRepository accountRepository;
    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest req;
    private MockHttpServletResponse resp;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenService, accountRepository);
        req = new MockHttpServletRequest();
        resp = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    private static Account account(UUID id, String username, AccountRole role, boolean active) {
        Account a = new Account();
        a.setId(id);
        a.setUsername(username);
        a.setRole(role);
        a.setActive(active);
        return a;
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void preservesPreExistingAuthWhenBearerIsUnparseableAsJwt() throws Exception {
        // Simulate ApiTokenAuthenticationFilter having already authenticated.
        AuthPrincipal apiTokenPrincipal = new AuthPrincipal(
                PrincipalType.SUPERADMIN, UUID.randomUUID(), "alice");
        var preExistingAuth = new UsernamePasswordAuthenticationToken(
                apiTokenPrincipal, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
        SecurityContextHolder.getContext().setAuthentication(preExistingAuth);

        // JWT filter receives a bearer that looks like an API token (not parseable as JWT).
        req.addHeader("Authorization", "Bearer ldap_pat_not_a_jwt");

        filter.doFilter(req, resp, chain);

        // Pre-existing authentication must survive untouched.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isSameAs(preExistingAuth);
        verify(jwtTokenService, never()).parseFull(any());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void preservesPreExistingAuthEvenIfBearerIsParseable() throws Exception {
        // Pre-existing authentication takes precedence over the JWT path,
        // even when the bearer IS a valid JWT — the upstream filter is
        // authoritative.
        AuthPrincipal apiTokenPrincipal = new AuthPrincipal(
                PrincipalType.SUPERADMIN, UUID.randomUUID(), "alice");
        var preExistingAuth = new UsernamePasswordAuthenticationToken(
                apiTokenPrincipal, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
        SecurityContextHolder.getContext().setAuthentication(preExistingAuth);

        req.addHeader("Authorization", "Bearer a.valid.jwt");

        filter.doFilter(req, resp, chain);

        // JWT filter must not overwrite the upstream-set authentication,
        // even though parse() would have succeeded.
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isSameAs(preExistingAuth);
        verify(jwtTokenService, never()).parseFull(any());
    }

    @Test
    void noPriorAuth_parsesJwtAndSetsContext() throws Exception {
        // Sanity check: the guard doesn't break the normal JWT path. The token
        // claims are confirmed against a live, active account.
        UUID id = UUID.randomUUID();
        AuthPrincipal parsed = new AuthPrincipal(PrincipalType.ADMIN, id, "bob");
        when(jwtTokenService.parseFull("valid.jwt.token"))
                .thenReturn(new JwtTokenService.ParsedJwt(parsed, 1L));
        when(accountRepository.findById(id))
                .thenReturn(Optional.of(account(id, "bob", AccountRole.ADMIN, true)));
        req.addHeader("Authorization", "Bearer valid.jwt.token");

        filter.doFilter(req, resp, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        AuthPrincipal principal = (AuthPrincipal) auth.getPrincipal();
        assertThat(principal.type()).isEqualTo(PrincipalType.ADMIN);
        assertThat(principal.id()).isEqualTo(id);
        assertThat(principal.username()).isEqualTo("bob");
    }

    @Test
    void noPriorAuth_invalidJwt_clearsContext() throws Exception {
        // Sanity check: existing error-path behavior is preserved.
        when(jwtTokenService.parseFull("bad.jwt")).thenThrow(new JwtException("malformed"));
        req.addHeader("Authorization", "Bearer bad.jwt");

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void deactivatedAccount_isNotAuthenticated() throws Exception {
        UUID id = UUID.randomUUID();
        when(jwtTokenService.parseFull("valid.jwt.token"))
                .thenReturn(new JwtTokenService.ParsedJwt(
                        new AuthPrincipal(PrincipalType.SUPERADMIN, id, "alice"), 1L));
        when(accountRepository.findById(id))
                .thenReturn(Optional.of(account(id, "alice", AccountRole.SUPERADMIN, false)));
        req.addHeader("Authorization", "Bearer valid.jwt.token");

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void deletedAccount_isNotAuthenticated() throws Exception {
        UUID id = UUID.randomUUID();
        when(jwtTokenService.parseFull("valid.jwt.token"))
                .thenReturn(new JwtTokenService.ParsedJwt(
                        new AuthPrincipal(PrincipalType.ADMIN, id, "ghost"), 1L));
        when(accountRepository.findById(id)).thenReturn(Optional.empty());
        req.addHeader("Authorization", "Bearer valid.jwt.token");

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void roleChangeInDb_overridesStaleTokenClaim() throws Exception {
        // Token was minted while alice was SUPERADMIN; she's since been demoted
        // to ADMIN. The granted authority must reflect the live role, not the
        // stale claim.
        UUID id = UUID.randomUUID();
        when(jwtTokenService.parseFull("valid.jwt.token"))
                .thenReturn(new JwtTokenService.ParsedJwt(
                        new AuthPrincipal(PrincipalType.SUPERADMIN, id, "alice"), 1L));
        when(accountRepository.findById(id))
                .thenReturn(Optional.of(account(id, "alice", AccountRole.ADMIN, true)));
        req.addHeader("Authorization", "Bearer valid.jwt.token");

        filter.doFilter(req, resp, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(((AuthPrincipal) auth.getPrincipal()).type()).isEqualTo(PrincipalType.ADMIN);
        assertThat(auth.getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void staleCredentialsVersion_isRejected() throws Exception {
        // Token carries cv=1 but the account has rotated to cv=2 — the
        // operator reset the password (or switched authType) since the
        // token was issued. Filter must refuse the stale credential.
        UUID id = UUID.randomUUID();
        when(jwtTokenService.parseFull("stale.jwt.token"))
                .thenReturn(new JwtTokenService.ParsedJwt(
                        new AuthPrincipal(PrincipalType.ADMIN, id, "bob"), 1L));
        Account rotated = account(id, "bob", AccountRole.ADMIN, true);
        rotated.setCredentialsVersion(2L);
        when(accountRepository.findById(id)).thenReturn(Optional.of(rotated));
        req.addHeader("Authorization", "Bearer stale.jwt.token");

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingCredentialsVersionClaim_isRejected() throws Exception {
        // Pre-deploy tokens lack the `cv` claim entirely. Reject so users
        // are forced to re-login once after the rollout — the same effect
        // as bumping every account's credentialsVersion.
        UUID id = UUID.randomUUID();
        when(jwtTokenService.parseFull("legacy.jwt.token"))
                .thenReturn(new JwtTokenService.ParsedJwt(
                        new AuthPrincipal(PrincipalType.ADMIN, id, "bob"), null));
        when(accountRepository.findById(id))
                .thenReturn(Optional.of(account(id, "bob", AccountRole.ADMIN, true)));
        req.addHeader("Authorization", "Bearer legacy.jwt.token");

        filter.doFilter(req, resp, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void selfServicePrincipal_isNotReloadedFromAccountRepository() throws Exception {
        // Self-service users are LDAP-backed, not Account rows — re-validating
        // them against AccountRepository would wrongly reject every request.
        UUID id = UUID.randomUUID();
        UUID directoryId = UUID.randomUUID();
        AuthPrincipal selfService = new AuthPrincipal(
                PrincipalType.SELF_SERVICE, id, "enduser", "cn=enduser,ou=people", directoryId);
        when(jwtTokenService.parseFull("valid.jwt.token"))
                .thenReturn(new JwtTokenService.ParsedJwt(selfService, null));
        req.addHeader("Authorization", "Bearer valid.jwt.token");

        filter.doFilter(req, resp, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isSameAs(selfService);
        verifyNoInteractions(accountRepository);
    }
}
