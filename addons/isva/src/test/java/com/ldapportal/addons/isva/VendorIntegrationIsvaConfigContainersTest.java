// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The user and group containers default to cn=Users / cn=Groups below the management DIT. */
class VendorIntegrationIsvaConfigContainersTest {

    private static final String DIT = "secAuthority=Default,cn=treasury";

    @Test
    void containersDefaultBelowTheManagementDit() {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setManagementDitBaseDn(DIT);

        assertThat(cfg.effectiveUserContainerDn()).isEqualTo("cn=Users," + DIT);
        assertThat(cfg.effectiveGroupContainerDn()).isEqualTo("cn=Groups," + DIT);
    }

    @Test
    void configuredContainersWin() {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setManagementDitBaseDn(DIT);
        cfg.setUserContainerDn(" ou=people," + DIT + " ");
        cfg.setGroupContainerDn("ou=roles," + DIT);

        assertThat(cfg.effectiveUserContainerDn()).isEqualTo("ou=people," + DIT);
        assertThat(cfg.effectiveGroupContainerDn()).isEqualTo("ou=roles," + DIT);
    }

    @Test
    void noManagementDitAndNoContainer_isNull() {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();

        assertThat(cfg.effectiveUserContainerDn()).isNull();
        assertThat(cfg.effectiveGroupContainerDn()).isNull();
    }
}
