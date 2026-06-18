// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import com.ldapportal.entity.enums.FeatureKey;

import java.util.Arrays;
import java.util.List;

/**
 * Runtime access to the current license and entitlement checks.
 *
 * <p>The implementation is {@link LicenseBackedEntitlementService},
 * which delegates to a {@link LicenseProvider}. Production installs get
 * {@link FileLicenseProvider} (signed JWT from disk); installs without a
 * license file fall back to {@link CommunityEditionLicenseProvider}
 * (community baseline — no entitlements).</p>
 */
public interface EntitlementService {

    /**
     * Current license snapshot. Implementations may cache this but must
     * honour settings/license changes within a short TTL (see
     * {@code DefaultEntitlementService} — no cache; reads settings live).
     */
    License current();

    /**
     * Shorthand for {@link License#has(Entitlement)} against the
     * current license.
     */
    default boolean has(Entitlement e) {
        return current().has(e);
    }

    /**
     * Throw {@link EntitlementMissingException} if the current license does
     * not grant {@code e}. Used by {@link EntitlementAspect} to gate
     * {@link Entitled}-annotated code.
     */
    default void requireEntitlement(Entitlement e) {
        License lic = current();
        if (!lic.has(e)) {
            throw new EntitlementMissingException(e, lic.edition());
        }
    }

    /**
     * Whether {@code feature} should be exposed in the permission-assignment
     * catalogue and effective-permissions viewer for the current license. A
     * feature gated by an entitlement (e.g. HR or SoD keys) is hidden when that
     * entitlement is absent, so non-Enterprise installs don't surface toggles
     * whose backing capability can never run. Core features (no required
     * entitlement) are always exposed.
     *
     * <p>This governs visibility only; execution stays gated by
     * {@link Entitled}.</p>
     */
    default boolean exposesFeature(FeatureKey feature) {
        return exposes(feature);
    }

    /**
     * Whether an {@link EditionScoped} catalogue constant should be exposed to
     * the UI under the current license. The single gate every "enumerate a
     * catalogue and ship it to the client" surface must consult: a constant
     * whose {@link EditionScoped#requiredEntitlement() required entitlement} is
     * absent is hidden so non-entitled values can't leak into the app. Core
     * constants (no required entitlement) are always exposed.
     *
     * <p>Governs visibility only; execution stays gated by {@link Entitled}.</p>
     */
    default boolean exposes(EditionScoped item) {
        Entitlement required = item.requiredEntitlement();
        return required == null || has(required);
    }

    /**
     * The exposed constants of an {@link EditionScoped} catalogue enum under the
     * current license, in declaration order. Use this instead of
     * {@code MyEnum.values()} whenever the result is serialised to the client,
     * so non-entitled constants are filtered out at the single, authoritative
     * gate rather than per surface.
     */
    default <T extends Enum<T> & EditionScoped> List<T> exposed(Class<T> catalog) {
        return Arrays.stream(catalog.getEnumConstants())
                .filter(this::exposes)
                .toList();
    }
}
