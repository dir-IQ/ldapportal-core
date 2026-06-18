// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import org.junit.jupiter.api.Test;

/**
 * Behavioural half of the edition-leak guard for core (the structural half is
 * {@code com.ldapportal.architecture.CatalogExposureArchitectureTest}).
 *
 * <p>The logic lives in {@link EditionLeakGuards} (shipped in core's test-jar)
 * so downstream modules — the {@code ee} edition, the assembled distributions —
 * can run the same check over their own classpath. Here it covers core.</p>
 */
class EditionLeakGuardTest {

    @Test
    void communityEditionExposesNoEntitlementGatedCatalogueConstant() {
        EditionLeakGuards.assertCommunityHidesAllGatedConstants("com.ldapportal");
    }
}
