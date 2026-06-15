// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.admin;

import com.ldapportal.entity.enums.FeatureKey;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Sets or clears a single feature permission override for an admin.
 *
 * <p>When {@code profileId} is {@code null} the override is admin-wide — it
 * applies across every profile the admin has access to. When a profile id is
 * supplied the override is scoped to that profile only and takes precedence
 * over any admin-wide override for the same feature.</p>
 */
public record FeaturePermissionRequest(
        @NotNull FeatureKey featureKey,
        boolean enabled,
        /** Optional — null means admin-wide (default). */
        UUID profileId) {

    /** Backwards-compatible ctor for callers that only know admin-wide overrides. */
    public FeaturePermissionRequest(FeatureKey featureKey, boolean enabled) {
        this(featureKey, enabled, null);
    }
}
