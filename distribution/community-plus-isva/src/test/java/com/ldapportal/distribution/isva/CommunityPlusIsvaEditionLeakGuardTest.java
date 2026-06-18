// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.distribution.isva;

import com.ldapportal.core.entitlement.EditionLeakGuards;
import org.junit.jupiter.api.Test;

/**
 * Edition-leak behavioural guard at the community-plus-ISVA distribution
 * boundary: over the assembled classpath (core + the ISVA addon), the community
 * edition must expose no entitlement-gated {@code EditionScoped} catalogue
 * constant. Shared logic from core's test-jar; see {@link EditionLeakGuards}.
 */
class CommunityPlusIsvaEditionLeakGuardTest {

    @Test
    void communityEditionExposesNoGatedConstantAcrossCoreAndAddons() {
        EditionLeakGuards.assertCommunityHidesAllGatedConstants("com.ldapportal");
    }
}
