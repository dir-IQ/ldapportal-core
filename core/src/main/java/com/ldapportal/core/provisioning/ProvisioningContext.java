// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import java.util.UUID;

/**
 * Per-operation context carried alongside the payload through the
 * {@link ProvisioningInterceptor} chain. Today it holds the resolved
 * {@link com.ldapportal.entity.ProvisioningProfile} id (when the
 * operation was driven by one); it is deliberately a value type
 * designed to grow — requesting principal, correlation id, dry-run
 * flag — without re-touching the SPI signature each time.
 *
 * <p>{@code profileId} is {@code null} for operations with no
 * resolved profile (an unprovisioned-OU path, or a legacy caller).
 * Interceptors that key off the profile must tolerate the null case
 * and fall back to directory-level behaviour — see
 * {@link #empty()}.</p>
 */
public record ProvisioningContext(UUID profileId) {

    private static final ProvisioningContext EMPTY = new ProvisioningContext(null);

    /** Context with no resolved profile — the unprovisioned-OU / legacy path. */
    public static ProvisioningContext empty() {
        return EMPTY;
    }

    /**
     * Context carrying the resolved profile id. A {@code null}
     * {@code profileId} collapses to {@link #empty()} so callers can
     * pass an optional resolution result straight through.
     */
    public static ProvisioningContext of(UUID profileId) {
        return profileId == null ? EMPTY : new ProvisioningContext(profileId);
    }
}
