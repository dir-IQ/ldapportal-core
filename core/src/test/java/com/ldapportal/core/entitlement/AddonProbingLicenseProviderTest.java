// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for the addon-probing decorator. The decorator's
 * job is to union {@link AddonProbe#probedEntitlements()} into the
 * base provider's {@link License#addOns()} while leaving every
 * other license field — and {@link LicenseProvider#source()} —
 * untouched.
 */
class AddonProbingLicenseProviderTest {

    /** Base license shape used by every test. Edition.COMMUNITY because
     * the most interesting decorator behaviour is "addon grants
     * something the base never would" — which is the community
     * deployment case. */
    private static final License BASE_COMMUNITY_LICENSE = new License(
            /* customerId */ null,
            Edition.COMMUNITY,
            /* addOns    */ Set.of(),
            /* limits    */ Map.of(),
            /* issuedAt  */ Instant.EPOCH,
            /* expiresAt */ Instant.MAX,
            /* signature */ null);

    private final LicenseProvider baseProvider = new StubBase(BASE_COMMUNITY_LICENSE,
            "community edition (ee classes not on classpath)");

    @Test
    void emptyProbeList_passesThrough_baseLicenseUnchanged() {
        // The community distribution's normal state: no addons loaded
        // → decorator must behave identically to the base.
        AddonProbingLicenseProvider decorated =
                new AddonProbingLicenseProvider(baseProvider, List.of());

        assertThat(decorated.current()).isEqualTo(BASE_COMMUNITY_LICENSE);
    }

    @Test
    void nullProbeList_treatedAsEmpty() {
        // Spring DI injects an empty list when no beans match, but
        // defensive-null is cheap and protects against direct
        // construction sites.
        AddonProbingLicenseProvider decorated =
                new AddonProbingLicenseProvider(baseProvider, null);

        assertThat(decorated.current()).isEqualTo(BASE_COMMUNITY_LICENSE);
    }

    @Test
    void singleProbe_unionsItsEntitlementsIntoAddOns() {
        AddonProbingLicenseProvider decorated = new AddonProbingLicenseProvider(
                baseProvider,
                List.of(stubProbe(Entitlement.SAML_ADMIN_SSO)));

        License got = decorated.current();
        assertThat(got.addOns()).containsExactly(Entitlement.SAML_ADMIN_SSO);
        // Every other field passes through unchanged.
        assertThat(got.edition()).isEqualTo(Edition.COMMUNITY);
        assertThat(got.customerId()).isNull();
        assertThat(got.limits()).isEmpty();
        assertThat(got.issuedAt()).isEqualTo(Instant.EPOCH);
        assertThat(got.expiresAt()).isEqualTo(Instant.MAX);
        assertThat(got.signature()).isNull();
    }

    @Test
    void multipleProbes_unionAllEntitlements() {
        AddonProbingLicenseProvider decorated = new AddonProbingLicenseProvider(
                baseProvider,
                List.of(
                        stubProbe(Entitlement.SAML_ADMIN_SSO),
                        stubProbe(Entitlement.AUDIT_LOG_SIGNING)));

        assertThat(decorated.current().addOns())
                .containsExactlyInAnyOrder(
                        Entitlement.SAML_ADMIN_SSO,
                        Entitlement.AUDIT_LOG_SIGNING);
    }

    @Test
    void probeReturningEntitlementBaseAlreadyHas_noDuplication() {
        // Set union semantics — the same entitlement granted by both
        // base and probe shows up exactly once.
        License baseWithIsva = new License(
                null, Edition.COMMUNITY,
                Set.of(Entitlement.SAML_ADMIN_SSO),
                Map.of(), Instant.EPOCH, Instant.MAX, null);
        LicenseProvider customBase = new StubBase(baseWithIsva, "stub");

        AddonProbingLicenseProvider decorated = new AddonProbingLicenseProvider(
                customBase,
                List.of(stubProbe(Entitlement.SAML_ADMIN_SSO)));

        assertThat(decorated.current().addOns())
                .containsExactly(Entitlement.SAML_ADMIN_SSO);
    }

    @Test
    void probeReturningEmptySet_doesNotAddNoise() {
        // An addon whose code is loaded but currently inactive returns
        // Set.of(); decorator must treat this as a no-op rather than
        // throwing or polluting the license.
        AddonProbingLicenseProvider decorated = new AddonProbingLicenseProvider(
                baseProvider,
                List.of(stubProbe(/* no entitlements */)));

        assertThat(decorated.current().addOns()).isEmpty();
    }

    @Test
    void source_alwaysDelegatesToBase() {
        // The decorator never invents a source string; operators
        // diagnosing "where is my license coming from" need the base
        // provider's answer.
        AddonProbingLicenseProvider decorated = new AddonProbingLicenseProvider(
                baseProvider,
                List.of(stubProbe(Entitlement.SAML_ADMIN_SSO)));

        assertThat(decorated.source())
                .isEqualTo("community edition (ee classes not on classpath)");
    }

    @Test
    void editionStaysCommunity_eventWithProbedAddOns() {
        // Explicit guard against "addon presence implies a higher
        // tier" — that would be wrong and would lie to compliance
        // reporting about what the customer bought.
        AddonProbingLicenseProvider decorated = new AddonProbingLicenseProvider(
                baseProvider,
                List.of(stubProbe(Entitlement.GOVERNANCE)));

        assertThat(decorated.current().edition()).isEqualTo(Edition.COMMUNITY);
    }

    @Test
    void worksOverLicenseBackedEntitlementService_endToEnd() {
        // Sanity: a probe-granted entitlement flows through the
        // full EntitlementService.has() path, not just the License
        // record's view.
        LicenseProvider decorated = new AddonProbingLicenseProvider(
                baseProvider,
                List.of(stubProbe(Entitlement.SAML_ADMIN_SSO)));
        EntitlementService entitlementService =
                new LicenseBackedEntitlementService(decorated);

        assertThat(entitlementService.has(Entitlement.SAML_ADMIN_SSO)).isTrue();
        assertThat(entitlementService.has(Entitlement.GOVERNANCE)).isFalse();
    }

    // ── stubs ────────────────────────────────────────────────────────

    private static AddonProbe stubProbe(Entitlement... granted) {
        Set<Entitlement> set = granted.length == 0 ? Set.of() : Set.of(granted);
        return () -> set;
    }

    private record StubBase(License license, String source) implements LicenseProvider {
        @Override public License current() { return license; }
        @Override public String source()   { return source;  }
    }
}
