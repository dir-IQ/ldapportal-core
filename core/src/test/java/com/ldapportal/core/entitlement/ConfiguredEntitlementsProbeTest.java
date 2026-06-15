// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredEntitlementsProbeTest {

    @Test
    void grantsAllowListedEntitlement() {
        assertThat(new ConfiguredEntitlementsProbe("DIRECTORY_SYNC").probedEntitlements())
                .containsExactly(Entitlement.DIRECTORY_SYNC);
    }

    @Test
    void refusesNonSelfGrantableEntitlement_butStillGrantsTheAllowListedOne() {
        // GOVERNANCE is a commercial/EE entitlement → must be refused; the
        // allow-listed DIRECTORY_SYNC in the same CSV is still granted.
        assertThat(new ConfiguredEntitlementsProbe("GOVERNANCE,DIRECTORY_SYNC").probedEntitlements())
                .containsExactly(Entitlement.DIRECTORY_SYNC);
    }

    @Test
    void refusesEverythingWhenOnlyCommercialRequested() {
        assertThat(new ConfiguredEntitlementsProbe("GOVERNANCE,HR_SYNC").probedEntitlements())
                .isEmpty();
    }

    @Test
    void ignoresUnknownTokensAndWhitespace() {
        assertThat(new ConfiguredEntitlementsProbe("  , NOPE ,  DIRECTORY_SYNC  ").probedEntitlements())
                .containsExactly(Entitlement.DIRECTORY_SYNC);
    }

    @Test
    void emptyConfigGrantsNothing() {
        assertThat(new ConfiguredEntitlementsProbe("").probedEntitlements()).isEmpty();
    }
}
