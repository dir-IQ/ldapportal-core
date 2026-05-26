// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates requests bearing an {@code Authorization: Bearer ldap_pat_*}
 * API token. Runs before {@link JwtAuthenticationFilter} in the chain.
 *
 * <p>On valid tokens, populates {@link SecurityContextHolder} with a
 * creator-identity {@link AuthPrincipal} and stashes
 * {@link ApiTokenAuthenticationDetails} on the authentication's
 * {@code details} field for audit code to read via
 * {@link AuthContextHelper#currentApiToken()}.</p>
 *
 * <p>On invalid tokens (unknown hash, revoked, expired, deactivated creator),
 * clears the security context and passes through — Spring Security's
 * {@code authenticationEntryPoint} in {@code SecurityConfig} returns the
 * uniform {@code ProblemDetail} 401 on protected endpoints. Matches
 * {@link JwtAuthenticationFilter}'s behaviour.</p>
 *
 * <p>On non-prefixed bearers (JWTs), passes through untouched for the
 * downstream {@link JwtAuthenticationFilter} to handle.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX    = "Bearer ";
    private static final String API_TOKEN_PREFIX = "ldap_pat_";

    private final ApiTokenService apiTokenService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String bearer = extractBearer(request);
        if (bearer == null || !bearer.startsWith(API_TOKEN_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        Optional<ApiTokenService.AuthenticationBundle> auth =
                apiTokenService.authenticate(bearer);

        if (auth.isEmpty()) {
            SecurityContextHolder.clearContext();
            chain.doFilter(request, response);
            return;
        }

        ApiTokenService.AuthenticationBundle bundle = auth.get();
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                bundle.principal(),
                null,
                List.of(new SimpleGrantedAuthority(bundle.authorityRole())));
        token.setDetails(bundle.details());
        SecurityContextHolder.getContext().setAuthentication(token);

        chain.doFilter(request, response);
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) return null;
        return header.substring(BEARER_PREFIX.length()).trim();
    }
}
