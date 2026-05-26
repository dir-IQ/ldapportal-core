// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.admin;

import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.BaseRole;
import com.ldapportal.entity.enums.FeatureKey;

import java.util.List;
import java.util.UUID;

/**
 * Computed, per-profile breakdown of what an admin account can actually do.
 *
 * <p>Answers "why can (or can't) admin X do Y in profile Z?" without requiring
 * superadmins to manually cross-reference three tables plus the hard-coded
 * {@code READONLY_DEFAULT_FEATURES} set. Each feature entry in every profile
 * carries a {@link PermissionSource} telling the UI which dimension of the
 * model produced the allow/deny outcome.</p>
 *
 * <p>Superadmin accounts are reported with {@code superadmin=true} and an
 * empty profiles list — they bypass all scoping and feature checks.</p>
 */
public record EffectivePermissionsResponse(
        UUID adminId,
        String username,
        AccountRole role,
        boolean superadmin,
        List<ProfileEffective> profiles) {

    public record ProfileEffective(
            UUID profileId,
            String profileName,
            UUID directoryId,
            String directoryName,
            BaseRole baseRole,
            String targetOuDn,
            List<FeatureEffective> features) {}

    public record FeatureEffective(
            FeatureKey key,
            /** Human-readable feature identifier ({@code user.create}, etc.) — denormalised for UI convenience. */
            String dbValue,
            boolean allowed,
            PermissionSource source) {}

    /**
     * Which part of the permission model produced the allow/deny outcome.
     * Ordered roughly from most-specific to least-specific so UI code can
     * render an intuitive precedence explanation.
     */
    public enum PermissionSource {
        /** An {@code admin_feature_permissions} row scoped to this profile explicitly enabled the feature. */
        PROFILE_OVERRIDE_ALLOW,
        /** An {@code admin_feature_permissions} row scoped to this profile explicitly disabled the feature. */
        PROFILE_OVERRIDE_DENY,
        /** An admin-wide {@code admin_feature_permissions} row (profile_id IS NULL) explicitly enabled the feature. */
        ADMIN_OVERRIDE_ALLOW,
        /** An admin-wide {@code admin_feature_permissions} row (profile_id IS NULL) explicitly disabled the feature. */
        ADMIN_OVERRIDE_DENY,
        /** The profile's {@link BaseRole#ADMIN} grants this feature by default. */
        BASE_ROLE_ADMIN,
        /** The feature is in the read-only default set ({@code READONLY_DEFAULT_FEATURES}). */
        BASE_ROLE_READ_ONLY_DEFAULT,
        /** The profile's {@link BaseRole#READ_ONLY} base role does not grant this feature. */
        BASE_ROLE_DENIED
    }
}
