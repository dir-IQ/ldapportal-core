// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks in the security boundary and live exposure of the Prometheus scrape
 * endpoint added in the observability Phase 0 work.
 *
 * <p>Two invariants this guards:</p>
 * <ol>
 *   <li>{@code /actuator/prometheus} exposes operational internals, so it is
 *       <b>superadmin-only</b> — unauthenticated callers get 401, authenticated
 *       admins get 403. It must never become public like {@code health}/{@code info}.</li>
 *   <li>The endpoint is actually wired (the {@code micrometer-registry-prometheus}
 *       dependency + {@code include: ...,prometheus} exposure), so a superadmin
 *       gets a 200 in Prometheus exposition format. A regression that drops the
 *       dependency or the exposure entry would 404 here.</li>
 * </ol>
 *
 * <p>{@code health} stays public — asserted so the broad {@code /actuator/**}
 * superadmin rule can't accidentally swallow the probe endpoints.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
// Spring Boot disables real metrics exporters inside @SpringBootTest by default
// (only the in-memory simple registry stays on), so the Prometheus scrape endpoint
// wouldn't register and these assertions couldn't run. This re-enables metrics
// export — mirroring production, where the exporter is on by default. Tracing stays
// off (no tracer on the classpath, and irrelevant here).
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("test")
class ActuatorMetricsSecurityTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void prometheus_endpoint_rejects_anonymous_callers() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void prometheus_endpoint_is_forbidden_for_non_superadmins() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void prometheus_endpoint_is_served_to_superadmin_in_exposition_format() throws Exception {
        mockMvc.perform(get("/actuator/prometheus").with(user("super").roles("SUPERADMIN")))
                .andExpect(status().isOk())
                // Default JVM binders register against the Prometheus registry, so the
                // scrape body always carries at least the jvm_* family — proves the
                // endpoint is live, not just security-reachable.
                .andExpect(content().string(containsString("jvm_")));
    }

    @Test
    void health_endpoint_stays_public() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
