// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.core.entitlement.Entitlement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IsvaAddonProbeTest {

    private final IsvaAddonProbe probe = new IsvaAddonProbe();

    @Test
    void grantsExactlyVendorIntegrationsIsva() {
        assertThat(probe.probedEntitlements())
                .containsExactly(Entitlement.VENDOR_INTEGRATIONS_ISVA);
    }

    @Test
    void addonName_isStable() {
        // The name appears in startup log output; renaming it would
        // confuse anyone diff'ing logs across deployments.
        assertThat(probe.addonName()).isEqualTo("isva");
    }
}
