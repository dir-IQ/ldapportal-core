// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.repository.AccountRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
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

/**
 * Extracts the JWT from the {@code Authorization: Bearer} header or, as a
 * fallback, from the {@code jwt} httpOnly cookie.  Validates the token and
 * populates {@link SecurityContextHolder} with the authenticated
 * {@link AuthPrincipal}.
 *
 * <p>Requests without a valid token pass through with an anonymous context;
 * Spring Security's {@code authorizeHttpRequests} rules then decide whether
 * to grant or deny access.</p>
 *
 * <p>For admin principals the backing {@link Account} is re-loaded on every
 * request and re-checked for {@code active} status and current role, so a
 * deactivated, deleted, or demoted admin loses access immediately rather than
 * riding an already-issued JWT until it expires. Self-service principals are
 * LDAP-backed (not {@code Account} rows) and are passed through unchanged.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String COOKIE_NAME   = "jwt";

    private final JwtTokenService jwtTokenService;
    private final AccountRepository accountRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        // Skip if a prior filter (e.g. ApiTokenAuthenticationFilter) already
        // populated the security context for this request.
        if (token != null
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                AuthPrincipal principal = resolve(jwtTokenService.parse(token));
                if (principal == null) {
                    // Token was valid but the account is gone / deactivated —
                    // refuse the stale credential instead of trusting claims.
                    SecurityContextHolder.clearContext();
                } else {
                    String role = "ROLE_" + principal.type().name(); // ROLE_SUPERADMIN, ROLE_ADMIN, or ROLE_SELF_SERVICE

                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            principal,
                            null,
                            List.of(new SimpleGrantedAuthority(role)));

                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException ex) {
                log.debug("JWT rejected: {}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Re-validates an admin principal against the live {@link Account} so role
     * and active-status changes take effect immediately. Returns a principal
     * rebuilt from current DB state, or {@code null} if the account no longer
     * exists or is deactivated. Self-service principals (LDAP-backed, no
     * {@code Account} row) are returned untouched.
     */
    private AuthPrincipal resolve(AuthPrincipal fromToken) {
        if (fromToken.type() == PrincipalType.SELF_SERVICE) {
            return fromToken;
        }
        return accountRepository.findById(fromToken.id())
                .filter(Account::isActive)
                .map(a -> new AuthPrincipal(
                        a.getRole() == AccountRole.SUPERADMIN
                                ? PrincipalType.SUPERADMIN
                                : PrincipalType.ADMIN,
                        a.getId(),
                        a.getUsername()))
                .orElse(null);
    }

    private String extractToken(HttpServletRequest request) {
        // 1. Authorization header (preferred — used by API clients and tests)
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }

        // 2. httpOnly cookie fallback (used by the browser SPA)
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        return null;
    }
}
