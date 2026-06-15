// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

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
}
