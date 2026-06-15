// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.admin;

import com.ldapportal.entity.AdminFeaturePermission;
import com.ldapportal.entity.AdminProfileRole;
import com.ldapportal.entity.enums.FeatureKey;

import java.util.List;
import java.util.UUID;

/**
 * Full permission summary for an admin account.
 */
public record AdminPermissionsResponse(
        List<ProfileRoleResponse> profileRoles,
        List<FeatureOverride> featurePermissions) {

    /**
     * A single feature override row. {@code profileId} is non-null when the
     * override is scoped to a specific profile; null means admin-wide.
     */
    public record FeatureOverride(FeatureKey featureKey, boolean enabled, UUID profileId) {
        public static FeatureOverride from(AdminFeaturePermission p) {
            UUID profileId = p.getProfile() != null ? p.getProfile().getId() : null;
            return new FeatureOverride(p.getFeatureKey(), p.isEnabled(), profileId);
        }
    }

    public static AdminPermissionsResponse from(
            List<AdminProfileRole> roles,
            List<AdminFeaturePermission> features) {

        List<ProfileRoleResponse> roleResponses =
                roles.stream().map(ProfileRoleResponse::from).toList();

        List<FeatureOverride> featureOverrides =
                features.stream().map(FeatureOverride::from).toList();

        return new AdminPermissionsResponse(roleResponses, featureOverrides);
    }
}
