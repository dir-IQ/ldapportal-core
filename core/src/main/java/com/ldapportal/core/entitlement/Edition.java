// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import java.util.Set;

/**
 * Commercial edition tier. Each edition carries a baseline set of
 * {@link Entitlement}s that every customer of that tier gets without
 * purchasing add-ons. Additional entitlements can be purchased as add-ons
 * and live in {@link License#addOns()} instead.
 *
 * <p>See {@code docs/edition-boundary.md} for the canonical mapping. This
 * file must stay in sync with that document — the document is the product
 * decision, this enum is the executable form.</p>
 */
public enum Edition {

    /**
     * Community edition (Apache 2.0). No ee entitlements. Ships without a
     * license file — the core build hardcodes this edition.
     */
    COMMUNITY(Set.of()),

    /**
     * Paid tier with professional support but no ee modules. Exists so
     * customers buying support aren't forced into a module they don't need.
     */
    TEAM(Set.of()),

    /**
     * Paid tier including the hybrid, events, alerting, and
     * service-accounts modules. Governance and HR are optional add-ons
     * at this tier.
     */
    BUSINESS(Set.of(
            Entitlement.HYBRID,
            Entitlement.EVENTS,
            Entitlement.ALERTING,
            Entitlement.SERVICE_ACCOUNTS,
            Entitlement.DIRECTORY_SYNC)),

    /**
     * Top tier: everything. No add-ons needed.
     */
    ENTERPRISE(Set.of(
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
            Entitlement.DATA_RESIDENCY));

    private final Set<Entitlement> baseline;

    Edition(Set<Entitlement> baseline) {
        this.baseline = Set.copyOf(baseline);
    }

    /**
     * Entitlements every customer of this edition receives without
     * purchasing add-ons. Immutable.
     */
    public Set<Entitlement> baselineEntitlements() {
        return baseline;
    }
}
