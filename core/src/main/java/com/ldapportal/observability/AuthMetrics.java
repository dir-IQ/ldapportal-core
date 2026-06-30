// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Counter for rejected authentication attempts (Phase 3c observability) — the
 * brute-force / credential-attack signal. Incremented at the auth-rejection
 * sites (admin login, API-token validation). The audit log keeps the
 * per-account detail; the metric keeps only bounded label dimensions.
 *
 * <p>Prometheus: {@code ldapportal_auth_failures_total{reason,principal}}.</p>
 */
@Component
@RequiredArgsConstructor
public class AuthMetrics {

    static final String FAILURES = "ldapportal.auth.failures";

    private final MeterRegistry registry;

    /**
     * Record one rejected authentication attempt. Both labels must be bounded,
     * low-cardinality values — never a username, IP, or token.
     *
     * @param reason    why it failed, e.g. {@code bad_credentials} / {@code invalid_token}
     * @param principal the credential surface, e.g. {@code admin} / {@code api_token}
     */
    public void recordFailure(String reason, String principal) {
        registry.counter(FAILURES, "reason", reason, "principal", principal).increment();
    }
}
