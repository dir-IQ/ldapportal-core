// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the auth-failure counter increments and partitions by the bounded
 * {@code reason} / {@code principal} labels.
 */
class AuthMetricsTest {

    @Test
    void records_failures_partitioned_by_reason_and_principal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthMetrics metrics = new AuthMetrics(registry);

        metrics.recordFailure("bad_credentials", "admin");
        metrics.recordFailure("bad_credentials", "admin");
        metrics.recordFailure("invalid_token", "api_token");

        assertThat(registry.get("ldapportal.auth.failures")
                .tag("reason", "bad_credentials").tag("principal", "admin").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("ldapportal.auth.failures")
                .tag("reason", "invalid_token").tag("principal", "api_token").counter().count())
                .isEqualTo(1.0);
    }
}
