// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

/**
 * SPI for obtaining the currently-active license. Two production
 * implementations:
 *
 * <ul>
 *   <li>{@link FileLicenseProvider} — reads a signed JWT from
 *       {@code ldapportal.license.path} and verifies it with
 *       {@link LicenseVerifier}. What commercial installs use in
 *       production.</li>
 *   <li>{@link CommunityEditionLicenseProvider} — the no-license
 *       fallback: community baseline entitlements (the empty set).
 *       Used on the community JAR and on a commercial JAR that has no
 *       signed license configured.</li>
 * </ul>
 *
 * <p>{@code LicenseAutoConfiguration} chooses one based on whether
 * the license file path is set and readable.</p>
 *
 * <p>Callers should normally not reach for this interface — use
 * {@link EntitlementService} instead, which asks the provider and
 * caches the answer.</p>
 */
public interface LicenseProvider {

    /**
     * The license this install is operating under. Never null.
     * Implementations guarantee the license is either a signed
     * file-derived license or the community baseline fallback; an
     * invalid or missing configured license file causes the provider
     * to refuse registration (not to return null here).
     */
    License current();

    /**
     * Human-readable one-liner describing where this provider got its
     * license — e.g. "/etc/ldapportal/license.jwt" or "community
     * baseline (no license file configured)". Used by the startup
     * banner and the admin license-status view.
     */
    String source();
}
