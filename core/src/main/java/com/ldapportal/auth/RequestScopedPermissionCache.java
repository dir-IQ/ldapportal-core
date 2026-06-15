// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.AdminFeaturePermission;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.repository.AdminFeaturePermissionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-HTTP-request cache for permission data. Avoids repeated DB queries when
 * multiple permission checks occur in a single request — the only check
 * pattern that re-hits the DB across calls today is the admin-wide feature
 * override lookup, which {@link #getAdminWideOverride} memoises.
 *
 * <p>Profile-role lookups are not cached here: the hot path in
 * {@link PermissionService#requireFeature} uses the directory-scoped
 * {@code findAllByAdminAccountIdAndProfileDirectoryId} query, which is
 * cheap enough that an extra request-scoped cache adds complexity without
 * a measurable win.</p>
 */
@Component
@RequestScope
public class RequestScopedPermissionCache {

    private final Map<FeatureKey, Optional<AdminFeaturePermission>> featureOverrides = new HashMap<>();

    /**
     * Fetch the admin-wide override (profile_id IS NULL) for the given feature,
     * memoised per request. Per-profile overrides are not cached here because
     * each check is already scoped to a single profile id, so re-reads within
     * a request are rare; if that assumption stops holding, add a second map
     * keyed on (profileId, featureKey).
     */
    public Optional<AdminFeaturePermission> getAdminWideOverride(
            UUID adminId, FeatureKey key, AdminFeaturePermissionRepository repo) {
        return featureOverrides.computeIfAbsent(key,
                k -> repo.findAdminWideOverride(adminId, k));
    }
}
