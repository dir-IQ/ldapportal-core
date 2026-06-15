// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiTokenAuthenticationFilterTest {

    @Mock private ApiTokenService apiTokenService;
    private ApiTokenAuthenticationFilter filter;
    private MockHttpServletRequest req;
    private MockHttpServletResponse resp;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new ApiTokenAuthenticationFilter(apiTokenService);
        req = new MockHttpServletRequest();
        resp = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthHeader_passesThroughWithCleanContext() throws Exception {
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void nonPrefixedBearer_passesThroughWithoutCallingService() throws Exception {
        req.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.abc.def");
        filter.doFilter(req, resp, chain);
        verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
        verify(apiTokenService, never()).authenticate(any());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void validPrefixedBearer_setsSecurityContextAndAllowsChain() throws Exception {
        UUID tokenId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        ApiTokenService.AuthenticationBundle bundle = new ApiTokenService.AuthenticationBundle(
                new AuthPrincipal(PrincipalType.SUPERADMIN, creatorId, "alice"),
                new ApiTokenAuthenticationDetails(tokenId, "ci-terraform"),
                "ROLE_SUPERADMIN");
        when(apiTokenService.authenticate("ldap_pat_valid")).thenReturn(Optional.of(bundle));
        req.addHeader("Authorization", "Bearer ldap_pat_valid");

        filter.doFilter(req, resp, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getPrincipal()).isInstanceOf(AuthPrincipal.class);
        assertThat(((AuthPrincipal) auth.getPrincipal()).id()).isEqualTo(creatorId);
        assertThat(auth.getAuthorities()).hasSize(1);
        assertThat(auth.getAuthorities().iterator().next().getAuthority())
                .isEqualTo("ROLE_SUPERADMIN");
        assertThat(auth.getDetails()).isInstanceOf(ApiTokenAuthenticationDetails.class);
        assertThat(((ApiTokenAuthenticationDetails) auth.getDetails()).tokenId())
                .isEqualTo(tokenId);

        verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
    }

    @Test
    void invalidPrefixedBearer_clearsContextAndPassesThrough() throws Exception {
        when(apiTokenService.authenticate("ldap_pat_invalid")).thenReturn(Optional.empty());
        req.addHeader("Authorization", "Bearer ldap_pat_invalid");

        filter.doFilter(req, resp, chain);

        // Context cleared, chain continues. Spring Security's entry point will
        // produce the ProblemDetail 401 on any protected endpoint.
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
    }
}
