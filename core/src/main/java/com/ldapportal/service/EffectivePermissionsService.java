// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.admin.EffectivePermissionsResponse;
import com.ldapportal.dto.admin.EffectivePermissionsResponse.FeatureEffective;
import com.ldapportal.dto.admin.EffectivePermissionsResponse.PermissionSource;
import com.ldapportal.dto.admin.EffectivePermissionsResponse.ProfileEffective;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.AdminFeaturePermission;
import com.ldapportal.entity.AdminProfileRole;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.BaseRole;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.AdminFeaturePermissionRepository;
import com.ldapportal.repository.AdminProfileRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Computes a human-readable "what can this admin actually do?" breakdown for
 * the superadmin UI. Mirrors the allow/deny rules enforced at runtime by
 * {@link com.ldapportal.auth.PermissionService} but emits every step of the
 * resolution as a {@link PermissionSource} so the UI can show the caller
 * which dimension won for each feature.
 */
@Service
@RequiredArgsConstructor
public class EffectivePermissionsService {

    /** Mirror of {@link com.ldapportal.auth.PermissionService#READONLY_DEFAULT_FEATURES}.
     *  Duplicated here so this service stays read-only of the enforcement
     *  service. If the canonical set changes, update both. */
    private static final Set<FeatureKey> READONLY_DEFAULT_FEATURES = Set.of(
            FeatureKey.BULK_EXPORT,
            FeatureKey.REPORTS_RUN,
            FeatureKey.DIRECTORY_BROWSE,
            FeatureKey.SCHEMA_READ,
            FeatureKey.USER_READ,
            FeatureKey.GROUP_READ,
            FeatureKey.APPROVAL_MANAGE
    );

    private final AccountRepository                  accountRepo;
    private final AdminProfileRoleRepository         profileRoleRepo;
    private final AdminFeaturePermissionRepository   featurePermissionRepo;

    @Transactional(readOnly = true)
    public EffectivePermissionsResponse resolve(UUID adminId) {
        Account admin = accountRepo.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", adminId));

        // Superadmins bypass everything — expose that explicitly instead of
        // trying to enumerate "all features allowed" in every profile.
        if (admin.getRole() == AccountRole.SUPERADMIN) {
            return new EffectivePermissionsResponse(
                    admin.getId(), admin.getUsername(), admin.getRole(), true, List.of());
        }

        // Index admin-wide overrides (profile IS NULL) by feature so the
        // per-feature resolution loop can look them up in O(1).
        Map<FeatureKey, AdminFeaturePermission> adminWideOverrides =
                featurePermissionRepo.findAdminWideOverrides(adminId).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                AdminFeaturePermission::getFeatureKey,
                                Function.identity(),
                                (a, b) -> a));

        // Per-profile overrides keyed on (profileId, featureKey).
        Map<ProfileFeatureKey, AdminFeaturePermission> profileOverrides =
                featurePermissionRepo.findProfileOverrides(adminId).stream()
                        .filter(p -> p.getProfile() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                p -> new ProfileFeatureKey(p.getProfile().getId(), p.getFeatureKey()),
                                Function.identity(),
                                (a, b) -> a));

        List<AdminProfileRole> roles = profileRoleRepo.findAllByAdminAccountId(adminId);

        List<ProfileEffective> profileSummaries = new ArrayList<>();
        for (AdminProfileRole role : roles) {
            ProvisioningProfile profile = role.getProfile();
            if (profile == null) continue;  // defensive; FK should prevent this
            profileSummaries.add(summarize(profile, role.getBaseRole(),
                    adminWideOverrides, profileOverrides));
        }
        // Stable ordering: directory name, then profile name. Makes the UI
        // deterministic across reloads.
        profileSummaries.sort(Comparator
                .comparing(ProfileEffective::directoryName, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(ProfileEffective::profileName, Comparator.nullsLast(String::compareToIgnoreCase)));

        return new EffectivePermissionsResponse(
                admin.getId(), admin.getUsername(), admin.getRole(), false, profileSummaries);
    }

    private ProfileEffective summarize(ProvisioningProfile profile,
                                        BaseRole baseRole,
                                        Map<FeatureKey, AdminFeaturePermission> adminWideOverrides,
                                        Map<ProfileFeatureKey, AdminFeaturePermission> profileOverrides) {
        List<FeatureEffective> features = Arrays.stream(FeatureKey.values())
                .map(fk -> resolveFeature(fk, baseRole, profile.getId(),
                        adminWideOverrides, profileOverrides))
                .sorted(Comparator.comparing(FeatureEffective::dbValue))
                .toList();

        String directoryName = profile.getDirectory() != null
                ? profile.getDirectory().getDisplayName() : null;
        UUID directoryId = profile.getDirectory() != null ? profile.getDirectory().getId() : null;

        return new ProfileEffective(
                profile.getId(), profile.getName(),
                directoryId, directoryName,
                baseRole, profile.getTargetOuDn(),
                features);
    }

    private FeatureEffective resolveFeature(FeatureKey feature,
                                             BaseRole baseRole,
                                             UUID profileId,
                                             Map<FeatureKey, AdminFeaturePermission> adminWideOverrides,
                                             Map<ProfileFeatureKey, AdminFeaturePermission> profileOverrides) {
        // Precedence (most specific first): per-profile override → admin-wide
        // override → base role default. Mirrors PermissionService evaluation
        // order so the UI explanation matches the runtime decision.
        AdminFeaturePermission profileOverride = profileOverrides.get(new ProfileFeatureKey(profileId, feature));
        if (profileOverride != null) {
            return new FeatureEffective(feature, feature.getDbValue(),
                    profileOverride.isEnabled(),
                    profileOverride.isEnabled() ? PermissionSource.PROFILE_OVERRIDE_ALLOW
                                                 : PermissionSource.PROFILE_OVERRIDE_DENY);
        }

        AdminFeaturePermission adminOverride = adminWideOverrides.get(feature);
        if (adminOverride != null) {
            return new FeatureEffective(feature, feature.getDbValue(),
                    adminOverride.isEnabled(),
                    adminOverride.isEnabled() ? PermissionSource.ADMIN_OVERRIDE_ALLOW
                                                : PermissionSource.ADMIN_OVERRIDE_DENY);
        }

        if (baseRole == BaseRole.ADMIN) {
            return new FeatureEffective(feature, feature.getDbValue(), true,
                    PermissionSource.BASE_ROLE_ADMIN);
        }

        // READ_ONLY base role
        if (READONLY_DEFAULT_FEATURES.contains(feature)) {
            return new FeatureEffective(feature, feature.getDbValue(), true,
                    PermissionSource.BASE_ROLE_READ_ONLY_DEFAULT);
        }
        return new FeatureEffective(feature, feature.getDbValue(), false,
                PermissionSource.BASE_ROLE_DENIED);
    }

    private record ProfileFeatureKey(UUID profileId, FeatureKey feature) {}
}
