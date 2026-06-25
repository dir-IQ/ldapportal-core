// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.core.entitlement.Edition;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.entitlement.License;
import com.ldapportal.core.entitlement.LimitType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the license overlay: entitlement flags, the info descriptor, the
 * hard-expired flag, and the sentinel-gated series (expiry timestamp + quota
 * limits) that are present only when the license carries a real expiry / finite
 * limit. {@link EntitlementService} is mocked to return crafted {@link License}
 * records.
 */
class LicenseMetricsTest {

    private EntitlementService entitlements;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        entitlements = mock(EntitlementService.class);
        registry = new SimpleMeterRegistry();
    }

    /** Build the component against a given license (current() is read in the constructor). */
    private LicenseMetrics metricsFor(License lic) {
        when(entitlements.current()).thenReturn(lic);
        LicenseMetrics m = new LicenseMetrics(entitlements, registry);
        m.refresh();
        return m;
    }

    @Test
    void community_baseline_emits_flags_but_no_expiry_or_quota_series() {
        metricsFor(new License(null, Edition.COMMUNITY, Set.of(), Map.of(),
                Instant.EPOCH, Instant.MAX, null));

        // every entitlement withheld
        assertThat(entitlement(Entitlement.GOVERNANCE)).isZero();
        assertThat(entitlement(Entitlement.DIRECTORY_SYNC)).isZero();
        // info descriptor present, unsigned community
        assertThat(registry.get("ldapportal.license.info")
                .tag("edition", "COMMUNITY").tag("signed", "false").gauge().value()).isEqualTo(1.0);
        // never expires
        assertThat(registry.get("ldapportal.license.expired").gauge().value()).isZero();
        // sentinel-gated series are absent
        assertThat(registry.find("ldapportal.license.expiry.timestamp.seconds").gauge()).isNull();
        assertThat(registry.find("ldapportal.usage.limit").gauge()).isNull();
    }

    @Test
    void signed_license_emits_granted_entitlements_expiry_and_quota() {
        Instant expiry = Instant.now().plus(Duration.ofDays(90));
        metricsFor(new License(UUID.randomUUID(), Edition.COMMUNITY,
                Set.of(Entitlement.GOVERNANCE, Entitlement.DIRECTORY_SYNC),
                Map.of(LimitType.DIRECTORIES, 25L),
                Instant.now(), expiry, "ed25519-signature"));

        assertThat(entitlement(Entitlement.GOVERNANCE)).isEqualTo(1.0);
        assertThat(entitlement(Entitlement.DIRECTORY_SYNC)).isEqualTo(1.0);
        assertThat(entitlement(Entitlement.HR_SYNC)).isZero();

        assertThat(registry.get("ldapportal.license.info")
                .tag("signed", "true").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("ldapportal.license.expired").gauge().value()).isZero();
        assertThat(registry.get("ldapportal.license.expiry.timestamp.seconds").gauge().value())
                .isEqualTo((double) expiry.getEpochSecond());
        assertThat(registry.get("ldapportal.usage.limit")
                .tag("resource", "directories").gauge().value()).isEqualTo(25.0);
    }

    @Test
    void past_expiry_sets_the_expired_flag() {
        Instant past = Instant.now().minus(Duration.ofDays(1));
        metricsFor(new License(UUID.randomUUID(), Edition.COMMUNITY, Set.of(), Map.of(),
                Instant.now().minus(Duration.ofDays(366)), past, "sig"));

        assertThat(registry.get("ldapportal.license.expired").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("ldapportal.license.expiry.timestamp.seconds").gauge().value())
                .isEqualTo((double) past.getEpochSecond());
    }

    private double entitlement(Entitlement e) {
        return registry.get("ldapportal.license.entitlement").tag("entitlement", e.name()).gauge().value();
    }
}
