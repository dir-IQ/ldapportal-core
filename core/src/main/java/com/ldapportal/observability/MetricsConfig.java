// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Metrics wiring for the LDAPPortal self-observability work.
 *
 * <ul>
 *   <li>Phase 0 — tag every meter with the application name so a shared
 *       Prometheus / Grafana can tell this service apart from others scraping
 *       the same registry.</li>
 *   <li>Phase 1 — give the LDAP operation timer a bounded latency histogram so
 *       percentile queries work without unbounded series growth.</li>
 * </ul>
 */
@Configuration
public class MetricsConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config().commonTags("application", "ldap-portal");
    }

    /**
     * Bounded latency histogram for the LDAP operation timer
     * ({@link LdapOperationMetrics#OPERATION_TIMER}). Explicit SLO buckets keep
     * the Prometheus series count predictable while still supporting
     * {@code histogram_quantile} for p95/p99 latency. The buckets span 5ms–10s,
     * the practical range for directory round-trips.
     */
    @Bean
    MeterFilter ldapOperationLatencyHistogram() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!LdapOperationMetrics.OPERATION_TIMER.equals(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(
                                Duration.ofMillis(5).toNanos(),
                                Duration.ofMillis(10).toNanos(),
                                Duration.ofMillis(25).toNanos(),
                                Duration.ofMillis(50).toNanos(),
                                Duration.ofMillis(100).toNanos(),
                                Duration.ofMillis(250).toNanos(),
                                Duration.ofMillis(500).toNanos(),
                                Duration.ofSeconds(1).toNanos(),
                                Duration.ofSeconds(2).toNanos(),
                                Duration.ofSeconds(5).toNanos(),
                                Duration.ofSeconds(10).toNanos())
                        .build()
                        .merge(config);
            }
        };
    }
}
