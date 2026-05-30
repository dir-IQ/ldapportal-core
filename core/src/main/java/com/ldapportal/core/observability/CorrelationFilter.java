// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Establishes the {@link CorrelationContext} scope for the lifetime of an
 * HTTP request. Reads the {@code X-Correlation-Id} request header when the
 * caller supplies a valid UUID (so an operator can trace a bulk operation
 * end-to-end), otherwise mints one. Echoes the resolved id back on the
 * response so the caller learns the id it can pivot on.
 *
 * <p>Runs early — before auth — so every downstream audit row (including
 * authentication-failure rows) carries the id. The {@code finally} clears
 * the ThreadLocal so a pooled request thread never leaks a stale id into
 * the next request.
 *
 * <p><b>Not a Spring bean.</b> Boot auto-registers any {@code Filter} bean
 * into the root servlet chain (running it on every path, outside the
 * security chain). To keep a single, security-chain-only registration this
 * filter is instantiated directly in {@code SecurityConfig} via
 * {@code addFilterBefore(new CorrelationFilter(), ...)} — it has no injected
 * dependencies (it only touches the static {@link CorrelationContext}).
 */
public class CorrelationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        UUID id = parseOrGenerate(request.getHeader(HEADER));
        CorrelationContext.set(id);
        response.setHeader(HEADER, id.toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }

    private static UUID parseOrGenerate(String headerValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            try {
                return UUID.fromString(headerValue.trim());
            } catch (IllegalArgumentException ignored) {
                // Malformed client-supplied id — fall through to a fresh one
                // rather than rejecting the request over a trace header.
            }
        }
        return UUID.randomUUID();
    }
}
