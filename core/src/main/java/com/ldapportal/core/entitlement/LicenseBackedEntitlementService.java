// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import lombok.RequiredArgsConstructor;

/**
 * {@link EntitlementService} that delegates to a {@link LicenseProvider}
 * for the current license. Replaces the Phase-1
 * {@code DefaultEntitlementService}, which read settings directly —
 * the indirection through a provider lets us swap in a
 * {@link FileLicenseProvider} (signed JWT) without changing every
 * call site that asks whether a feature is entitled.
 *
 * <p>Callers continue to use {@link EntitlementService} — this class
 * is only registered by the auto-configuration and isn't referenced
 * directly by application code.</p>
 */
@RequiredArgsConstructor
public class LicenseBackedEntitlementService implements EntitlementService {

    private final LicenseProvider licenseProvider;

    @Override
    public License current() {
        return licenseProvider.current();
    }
}
