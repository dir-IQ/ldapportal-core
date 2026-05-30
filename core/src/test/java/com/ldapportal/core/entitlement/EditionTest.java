// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the edition baselines declared in {@link Edition} against drift
 * from {@code docs/edition-boundary.md}.
 */
class EditionTest {

    @Test
    void community_hasEmptyBaseline() {
        assertThat(Edition.COMMUNITY.baselineEntitlements()).isEmpty();
    }

    @Test
    void team_hasEmptyBaseline() {
        // TEAM is a support-only paid tier; no ee modules baselined
        assertThat(Edition.TEAM.baselineEntitlements()).isEmpty();
    }

    @Test
    void business_baselinesPlatformTeamModules() {
        // Business baseline: modules that target the platform-team buyer
        // (hybrid / events / alerting / service accounts). Governance and
        // HR are Business add-ons, not baseline. See edition-boundary.md.
        assertThat(Edition.BUSINESS.baselineEntitlements())
                .containsExactlyInAnyOrder(
                        Entitlement.HYBRID,
                        Entitlement.EVENTS,
                        Entitlement.ALERTING,
                        Entitlement.SERVICE_ACCOUNTS,
                        Entitlement.DIRECTORY_SYNC);
    }

    @Test
    void enterprise_baselinesAllTierEntitlements() {
        // ENTERPRISE includes every commercial-tier entitlement, but
        // NOT addon-granted entitlements (those are classpath-probed,
        // not tier-owned — see AddonProbe / Entitlement enum block
        // comment). Asserted by name rather than `Entitlement.values()`
        // so adding a new addon entitlement doesn't accidentally
        // flip the meaning of the contract.
        assertThat(Edition.ENTERPRISE.baselineEntitlements())
                .containsExactlyInAnyOrder(
                        Entitlement.GOVERNANCE,
                        Entitlement.HYBRID,
                        Entitlement.EVENTS,
                        Entitlement.HR_SYNC,
                        Entitlement.ALERTING,
                        Entitlement.SERVICE_ACCOUNTS,
                        Entitlement.DIRECTORY_SYNC,
                        Entitlement.SAML_ADMIN_SSO,
                        Entitlement.AUDIT_LOG_SIGNING,
                        Entitlement.HA_DEPLOYMENT,
                        Entitlement.DATA_RESIDENCY);
        // Addon entitlement explicitly NOT in any tier baseline.
        assertThat(Edition.ENTERPRISE.baselineEntitlements())
                .doesNotContain(Entitlement.VENDOR_INTEGRATIONS_ISVA);
    }

    @Test
    void baselineEntitlements_isImmutable() {
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> Edition.BUSINESS.baselineEntitlements().add(Entitlement.HR_SYNC));
    }
}
