// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CommunityEditionLicenseProviderTest {

    private final CommunityEditionLicenseProvider provider =
            new CommunityEditionLicenseProvider();
    private final LicenseBackedEntitlementService entitlementService =
            new LicenseBackedEntitlementService(provider);

    @Test
    void reports_communityEdition() {
        assertThat(entitlementService.current().edition()).isEqualTo(Edition.COMMUNITY);
    }

    @Test
    void grants_noEnterpriseEntitlements() {
        // Every ee/-backed entitlement must be withheld — the whole point
        // of this provider is to suppress UI/API features whose
        // implementation isn't on the classpath.
        for (Entitlement e : Entitlement.values()) {
            assertThat(entitlementService.has(e))
                    .as("Entitlement %s should NOT be granted on community", e)
                    .isFalse();
        }
    }

    @Test
    void requireEntitlement_throws_carriesEditionContext() {
        EntitlementMissingException ex = org.junit.jupiter.api.Assertions.assertThrows(
                EntitlementMissingException.class,
                () -> entitlementService.requireEntitlement(Entitlement.GOVERNANCE));
        assertThat(ex.getEntitlement()).isEqualTo(Entitlement.GOVERNANCE);
        assertThat(ex.getCurrentEdition()).isEqualTo(Edition.COMMUNITY);
    }

    @Test
    void license_unsignedAndNeverExpires() {
        assertThat(provider.current().signature()).isNull();
        assertThat(provider.current().customerId()).isNull();
        assertThat(provider.current().expiresAt()).isEqualTo(Instant.MAX);
    }

    @Test
    void source_identifies_community_baseline() {
        assertThat(provider.source())
                .contains("community")
                .contains("no license file");
    }
}
