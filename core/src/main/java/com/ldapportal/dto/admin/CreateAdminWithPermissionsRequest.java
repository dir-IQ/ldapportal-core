// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.admin;

import jakarta.validation.Valid;

import java.util.List;

/**
 * Combined-create request: account fields plus the initial set of profile
 * role assignments and feature-permission overrides, persisted in one
 * transaction. Lets the superadmin UI present a "create admin with
 * permissions" flow that doesn't require a two-step "create then come
 * back and edit permissions" round-trip.
 *
 * <p>Both lists may be empty. They are validated and applied in order:
 * account is persisted first, then profile roles, then feature
 * permissions. Any failure rolls the whole transaction back so no half-
 * created admin lands in the table.</p>
 */
public record CreateAdminWithPermissionsRequest(
        @Valid AdminAccountRequest account,
        List<@Valid ProfileRoleRequest> profileRoles,
        List<@Valid FeaturePermissionRequest> featurePermissions) {

    public List<ProfileRoleRequest> profileRolesOrEmpty() {
        return profileRoles != null ? profileRoles : List.of();
    }

    public List<FeaturePermissionRequest> featurePermissionsOrEmpty() {
        return featurePermissions != null ? featurePermissions : List.of();
    }
}
