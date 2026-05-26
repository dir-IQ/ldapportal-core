// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProvisioningContextTest {

    @Test
    void of_carriesResolvedProfileId() {
        UUID profileId = UUID.randomUUID();
        ProvisioningContext ctx = ProvisioningContext.of(profileId);
        assertThat(ctx.profileId()).isEqualTo(profileId);
    }

    @Test
    void empty_hasNoProfile() {
        assertThat(ProvisioningContext.empty().profileId()).isNull();
    }

    @Test
    void of_null_collapsesToEmpty() {
        // A null resolution result is the unprovisioned-OU / legacy
        // path — it must be indistinguishable from empty().
        assertThat(ProvisioningContext.of(null)).isSameAs(ProvisioningContext.empty());
        assertThat(ProvisioningContext.of(null).profileId()).isNull();
    }
}
