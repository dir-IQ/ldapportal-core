// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseTest {

    @Test
    void has_returnsTrue_forEntitlementInEditionBaseline() {
        License lic = license(Edition.BUSINESS, Set.of());
        // HYBRID is part of BUSINESS baseline per the edition-boundary doc
        assertThat(lic.has(Entitlement.HYBRID)).isTrue();
    }

    @Test
    void has_returnsTrue_forEntitlementInAddOns() {
        License lic = license(Edition.BUSINESS, Set.of(Entitlement.GOVERNANCE));
        // GOVERNANCE is not in BUSINESS baseline but is purchased as add-on
        assertThat(lic.has(Entitlement.GOVERNANCE)).isTrue();
    }

    @Test
    void has_returnsFalse_forEntitlementInNeitherBaselineNorAddOns() {
        License lic = license(Edition.BUSINESS, Set.of());
        assertThat(lic.has(Entitlement.HR_SYNC)).isFalse();
    }

    @Test
    void community_edition_has_noEntitlementsByDefault() {
        License lic = license(Edition.COMMUNITY, Set.of());
        for (Entitlement e : Entitlement.values()) {
            assertThat(lic.has(e))
                    .as("COMMUNITY should not baseline " + e)
                    .isFalse();
        }
    }

    @Test
    void enterprise_edition_has_allTierEntitlementsInBaseline() {
        // Tier entitlements (every commercial feature) are baselined
        // on ENTERPRISE; addon-granted entitlements like
        // VENDOR_INTEGRATIONS_ISVA are explicitly NOT — they're
        // classpath-probed independently of tier.
        License lic = license(Edition.ENTERPRISE, Set.of());
        for (Entitlement e : Edition.ENTERPRISE.baselineEntitlements()) {
            assertThat(lic.has(e))
                    .as("ENTERPRISE should baseline " + e)
                    .isTrue();
        }
        // Addon entitlement not baselined → has() = false without the
        // addon classpath presence (which is the AddonProbe's job).
        assertThat(lic.has(Entitlement.VENDOR_INTEGRATIONS_ISVA))
                .as("VENDOR_INTEGRATIONS_ISVA is addon-granted, not tier-baselined")
                .isFalse();
    }

    @Test
    void limitFor_returnsMaxValue_whenLimitNotSpecified() {
        License lic = license(Edition.COMMUNITY, Set.of());
        assertThat(lic.limitFor(LimitType.DIRECTORIES)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void limitFor_returnsSpecifiedLimit() {
        License lic = new License(null, Edition.TEAM, Set.of(),
                Map.of(LimitType.DIRECTORIES, 5L),
                Instant.EPOCH, Instant.MAX, null);
        assertThat(lic.limitFor(LimitType.DIRECTORIES)).isEqualTo(5L);
    }

    @Test
    void isExpired_trueAfterExpiresAt() {
        Instant expires = Instant.now().minus(1, ChronoUnit.HOURS);
        License lic = new License(null, Edition.TEAM, Set.of(), Map.of(),
                Instant.EPOCH, expires, null);
        assertThat(lic.isExpired(Instant.now())).isTrue();
    }

    @Test
    void isExpired_falseBeforeExpiresAt() {
        Instant expires = Instant.now().plus(1, ChronoUnit.HOURS);
        License lic = new License(null, Edition.TEAM, Set.of(), Map.of(),
                Instant.EPOCH, expires, null);
        assertThat(lic.isExpired(Instant.now())).isFalse();
    }

    @Test
    void isExpired_falseForInstantMax() {
        License lic = new License(null, Edition.COMMUNITY, Set.of(), Map.of(),
                Instant.EPOCH, Instant.MAX, null);
        assertThat(lic.isExpired(Instant.now())).isFalse();
    }

    @Test
    void constructor_defensiveCopiesAddOns() {
        Set<Entitlement> mutable = new java.util.HashSet<>(Set.of(Entitlement.GOVERNANCE));
        License lic = new License(UUID.randomUUID(), Edition.TEAM, mutable, Map.of(),
                Instant.EPOCH, Instant.MAX, null);
        mutable.add(Entitlement.HR_SYNC);
        assertThat(lic.has(Entitlement.HR_SYNC)).isFalse();
    }

    @Test
    void constructor_handlesNullCollections() {
        License lic = new License(null, Edition.COMMUNITY, null, null,
                Instant.EPOCH, Instant.MAX, null);
        assertThat(lic.addOns()).isEmpty();
        assertThat(lic.limits()).isEmpty();
    }

    private static License license(Edition edition, Set<Entitlement> addOns) {
        return new License(UUID.randomUUID(), edition, addOns, Map.of(),
                Instant.EPOCH, Instant.MAX, null);
    }
}
