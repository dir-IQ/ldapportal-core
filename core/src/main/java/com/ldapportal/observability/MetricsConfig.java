// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metrics wiring. Phase 0 of the LDAPPortal self-observability work: tag every
 * meter with the application name so a shared Prometheus / Grafana can tell this
 * service apart from others scraping the same registry.
 */
@Configuration
public class MetricsConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config().commonTags("application", "ldap-portal");
    }
}
