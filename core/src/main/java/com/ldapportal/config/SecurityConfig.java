// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.ApiTokenAuthenticationFilter;
import com.ldapportal.auth.JwtAuthenticationFilter;
import com.ldapportal.config.AppProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Production security configuration — stateless JWT authentication via
 * httpOnly cookie (with Authorization: Bearer header as a fallback for
 * API clients and tests).
 *
 * <p>Public endpoints:
 * <ul>
 *   <li>{@code POST /api/auth/login}  — obtain a JWT cookie</li>
 *   <li>{@code POST /api/auth/logout} — clear the JWT cookie</li>
 *   <li>{@code GET  /actuator/health} — health probe (no auth required)</li>
 * </ul>
 * All other requests require a valid JWT (cookie or Bearer header).
 * </p>
 *
 * <p>The session is stateless; no {@code HttpSession} is created by Spring Security.
 * CSRF is disabled because state-changing calls are authenticated via JWT, which
 * is not automatically attached by the browser in the same way cookies are for
 * cross-site requests (the cookie is SameSite=Strict).</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter,
                                           ApiTokenAuthenticationFilter apiTokenFilter,
                                           ObjectMapper objectMapper) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // ── Security response headers ──────────────────────────────────────
            .headers(h -> h
                .frameOptions(fo -> fo.deny())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
                .contentTypeOptions(cto -> {})
                .contentSecurityPolicy(csp ->
                    csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(apiTokenFilter, JwtAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // ── Public ────────────────────────────────────────────────────
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/logout").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/self-service/login").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/setup-status").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/oidc/authorize").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/oidc/callback").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/webseal/authorize").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/settings/branding").permitAll()
                .requestMatchers("/api/v1/self-service/register/**").permitAll()
                .requestMatchers("/api/v1/auditor/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // Build identifier for the frontend's deployment-skew banner —
                // unauthenticated so it can render on the login page too, and
                // carries no sensitive info (just the git short-SHA + version).
                .requestMatchers(HttpMethod.GET, "/api/v1/version").permitAll()
                // Public OpenAPI spec — the contract. Endpoints it documents still enforce auth.
                .requestMatchers("/api/v1/openapi", "/api/v1/openapi.yaml", "/api/v1/openapi/**").permitAll()
                // Swagger UI + legacy springdoc paths stay operator-only (UI lets you execute calls).
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").hasRole("SUPERADMIN")
                // ── Self-service portal (SELF_SERVICE principal only) ─────────
                .requestMatchers("/api/v1/self-service/**").hasRole("SELF_SERVICE")
                // ── Superadmin-only management plane ──────────────────────────
                .requestMatchers("/api/v1/superadmin/**").hasRole("SUPERADMIN")
                // ── Admin endpoints (SUPERADMIN or ADMIN — exclude SELF_SERVICE) ──
                .requestMatchers("/api/v1/**").hasAnyRole("SUPERADMIN", "ADMIN")
                // ── Everything else ───────────────────────────────────────────
                .anyRequest().authenticated()
            )
            // Return RFC 7807 ProblemDetail on missing/invalid JWT (not plain-text 401)
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                            HttpStatus.UNAUTHORIZED, "Authentication required");
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    objectMapper.writeValue(res.getOutputStream(), pd);
                }));

        return http.build();
    }

    /**
     * CORS policy for the REST API.
     *
     * <p>When {@code CORS_ALLOWED_ORIGIN} is set, only that origin is
     * permitted (with credentials).  When unset, no {@link CorsConfiguration}
     * is registered, so Spring Security skips CORS processing entirely and
     * the browser's default Same-Origin Policy applies — which is the correct
     * default for single-origin deployments.</p>
     *
     * <p><strong>Important:</strong> registering a {@link CorsConfiguration}
     * with an empty allowed-origins list causes Spring to reject any request
     * that carries an {@code Origin} header (including same-site POSTs from
     * some browsers and dev-proxy setups).  That is why we register nothing
     * when the env var is absent.</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        String allowedOrigin = System.getenv("CORS_ALLOWED_ORIGIN");
        if (allowedOrigin != null && !allowedOrigin.isBlank()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(List.of(allowedOrigin));
            config.setAllowCredentials(true); // required for cookie-based auth
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With"));
            config.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/v1/**", config);
        }
        // Auditor portal: open CORS (public by design, token is the credential).
        // Uses allowedOriginPatterns("*") instead of allowedOrigins("*") to avoid
        // conflict with credentialed CORS on /api/v1/**.
        CorsConfiguration auditorCors = new CorsConfiguration();
        auditorCors.setAllowedOriginPatterns(List.of("*"));
        auditorCors.setAllowCredentials(false);
        auditorCors.setAllowedMethods(List.of("GET", "OPTIONS"));
        auditorCors.setAllowedHeaders(List.of("*"));
        auditorCors.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/v1/auditor/**", auditorCors);

        return source;
    }
}
