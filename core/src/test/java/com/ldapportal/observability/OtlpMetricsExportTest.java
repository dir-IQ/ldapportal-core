// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import io.micrometer.core.instrument.Clock;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 — OTLP (OpenTelemetry) metrics export.
 *
 * <p>The same Micrometer meters scraped at {@code /actuator/prometheus} can also
 * be pushed to an OTLP collector. Upstream, merely adding
 * {@code micrometer-registry-otlp} enables the exporter by default and pushes to
 * {@code localhost:4318} every step — so the app ships it disabled
 * ({@code management.otlp.metrics.export.enabled=false}) and flips it on
 * per-deployment. These tests pin that gate so a future change can't silently
 * start exporting.</p>
 *
 * <p>Only {@link OtlpMetricsExportAutoConfiguration} is loaded (plus a
 * {@link Clock}, which it needs): it self-registers {@code OpenTelemetryProperties},
 * so this mirrors the real runtime where the OpenTelemetry SDK is absent and the
 * SDK-gated {@code OpenTelemetryAutoConfiguration} stays inert.</p>
 */
class OtlpMetricsExportTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(Clock.class, () -> Clock.SYSTEM)
            .withConfiguration(AutoConfigurations.of(OtlpMetricsExportAutoConfiguration.class));

    @Test
    void otlpRegistry_absent_whenExportDisabled() {
        runner.withPropertyValues("management.otlp.metrics.export.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(OtlpMeterRegistry.class));
    }

    @Test
    void otlpRegistry_present_whenExportEnabledWithUrl() {
        runner.withPropertyValues(
                        "management.otlp.metrics.export.enabled=true",
                        "management.otlp.metrics.export.url=http://localhost:4318/v1/metrics")
                .run(ctx -> assertThat(ctx).hasSingleBean(OtlpMeterRegistry.class));
    }
}
