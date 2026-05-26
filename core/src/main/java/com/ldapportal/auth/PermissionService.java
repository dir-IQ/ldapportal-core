// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.AdminProfileRole;
import com.ldapportal.entity.enums.BaseRole;
import com.ldapportal.entity.enums.FeatureKey;

import com.ldapportal.repository.AdminFeaturePermissionRepository;
import com.ldapportal.repository.AdminProfileRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Enforces the permission model for admins.
 *
 * <h3>Dimensions</h3>
 * <ol>
 *   <li><b>Profile access</b> — admin must have a row in
 *       {@code admin_profile_roles} for the target profile.</li>
 *   <li><b>Base role</b> — {@code ADMIN} grants all default capabilities;
 *       {@code READ_ONLY} grants only the read/export subset.</li>
 *   <li><b>Feature override</b> — a row in {@code admin_feature_permissions}
 *       explicitly enables or disables a feature, overriding the base-role
 *       default.</li>
 * </ol>
 *
 * <p>Superadmins bypass all checks and are always granted access.</p>
 *
 * <h3>Directory vs profile</h3>
 * <p>A directory can host multiple profiles. The controller layer passes a
 * {@code directoryId} (the LDAP connection identifier). Directory-level
 * access is granted when the admin has a role in <em>any</em> profile that
 * belongs to that directory.</p>
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    /**
     * Features available to {@link BaseRole#READ_ONLY} admins by default.
     * Everything else requires {@link BaseRole#ADMIN}.
     */
    private static final Set<FeatureKey> READONLY_DEFAULT_FEATURES = Set.of(
            FeatureKey.BULK_EXPORT,
            FeatureKey.REPORTS_RUN,
            FeatureKey.DIRECTORY_BROWSE,
            FeatureKey.SCHEMA_READ,
            FeatureKey.USER_READ,
            FeatureKey.GROUP_READ,
            FeatureKey.APPROVAL_MANAGE
    );

    private final AdminProfileRoleRepository        profileRoleRepo;
    private final AdminFeaturePermissionRepository   featurePermissionRepo;
    private final ObjectProvider<RequestScopedPermissionCache> cacheProvider;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Verifies that {@code principal} has access to {@code profileId}
     * (dimensions 1 + 2) and returns the resolved role.
     *
     * @throws AccessDeniedException if no role is assigned
     */
    public AdminProfileRole requireProfileAccess(AuthPrincipal principal, UUID profileId) {
        if (principal.isSuperadmin()) {
            return null; // superadmin — no role row required
        }
        return profileRoleRepo
                .findByAdminAccountIdAndProfileId(principal.id(), profileId)
                .orElseThrow(() -> new AccessDeniedException(
                        "No access to profile [" + profileId + "]"));
    }

    /**
     * Verifies that {@code principal} has access to at least one profile in
     * the given directory (dimensions 1 + 2).
     *
     * @throws AccessDeniedException if the admin has no profile roles for this directory
     */
    public void requireDirectoryAccess(AuthPrincipal principal, UUID directoryId) {
        if (principal.isSuperadmin()) return;

        boolean hasAccess = profileRoleRepo
                .existsByAdminAccountIdAndProfileDirectoryId(principal.id(), directoryId);
        if (!hasAccess) {
            throw new AccessDeniedException("No access to directory [" + directoryId + "]");
        }
    }

    /**
     * Checks directory access (dim 1+2) and feature permission (dims 3+4) in
     * one call. Called from {@link FeaturePermissionAspect} with the
     * {@code directoryId} extracted from the controller method parameter.
     *
     * <h3>Resolution order per profile</h3>
     * <ol>
     *   <li>Per-profile override (most specific).</li>
     *   <li>Admin-wide override (profile_id IS NULL).</li>
     *   <li>Base role default — {@link BaseRole#ADMIN} grants everything;
     *       {@link BaseRole#READ_ONLY} only grants {@link #READONLY_DEFAULT_FEATURES}.</li>
     * </ol>
     *
     * <p>Across multiple profiles in the directory, <strong>most-permissive
     * wins</strong> — a request is allowed if at least one of the admin's
     * profile assignments in that directory resolves to "allowed". A
     * per-profile deny therefore only bites when no other profile grants the
     * feature. The effective-permissions viewer surfaces per-profile
     * resolution so superadmins can still see each profile's individual
     * verdict even though the gate itself is directory-scoped.</p>
     *
     * @throws AccessDeniedException if no profile resolves the feature as allowed
     */
    public void requireFeature(AuthPrincipal principal, UUID directoryId, FeatureKey feature) {
        if (principal.isSuperadmin()) return;

        requireDirectoryAccess(principal, directoryId);

        var cache = cacheProvider.getIfAvailable();
        var adminWideOverride = cache != null
                ? cache.getAdminWideOverride(principal.id(), feature, featurePermissionRepo)
                : featurePermissionRepo.findAdminWideOverride(principal.id(), feature);

        java.util.List<AdminProfileRole> roles = profileRoleRepo
                .findAllByAdminAccountIdAndProfileDirectoryId(principal.id(), directoryId);

        for (AdminProfileRole role : roles) {
            if (resolveFeatureForProfile(principal.id(), role, feature, adminWideOverride)) {
                return;
            }
        }

        throw new AccessDeniedException(
                "Feature [" + feature.getDbValue() + "] is not granted by any profile for this admin");
    }

    /**
     * Apply the per-profile precedence chain and return true when the feature
     * resolves to "allowed" for the given profile assignment.
     */
    private boolean resolveFeatureForProfile(UUID adminId,
                                              AdminProfileRole role,
                                              FeatureKey feature,
                                              java.util.Optional<com.ldapportal.entity.AdminFeaturePermission> adminWideOverride) {
        UUID profileId = role.getProfile() != null ? role.getProfile().getId() : null;
        if (profileId != null) {
            var profileOverride = featurePermissionRepo.findProfileOverride(adminId, profileId, feature);
            if (profileOverride.isPresent()) return profileOverride.get().isEnabled();
        }
        if (adminWideOverride.isPresent()) return adminWideOverride.get().isEnabled();
        if (role.getBaseRole() == BaseRole.ADMIN) return true;
        return READONLY_DEFAULT_FEATURES.contains(feature);
    }

    /**
     * Returns the set of directory IDs the admin has access to (via profile roles).
     * Returns empty set for superadmins (meaning unrestricted).
     */
    public Set<UUID> getAuthorizedDirectoryIds(AuthPrincipal principal) {
        if (principal.isSuperadmin()) {
            return Set.of();
        }
        return profileRoleRepo.findDistinctDirectoryIdsByAdminAccountId(principal.id());
    }

    // ── DN-level scoping (Wave 4.7 Option A) ───────────────────────────────

    /**
     * Returns the set of OU DNs the admin is authorized to operate in for
     * the given directory (derived from profile {@code targetOuDn} fields).
     * Returns empty set for superadmins (meaning unrestricted).
     */
    public Set<String> getAuthorizedOuDns(AuthPrincipal principal, UUID directoryId) {
        if (principal.isSuperadmin()) return Set.of();
        return profileRoleRepo
                .findAllByAdminAccountIdAndProfileDirectoryIdWithProfile(principal.id(), directoryId)
                .stream()
                .map(r -> r.getProfile().getTargetOuDn())
                .collect(Collectors.toSet());
    }

    /**
     * Verifies that the given DN falls within one of the admin's authorized OUs
     * in the specified directory. A DN is in-scope if it is equal to or a
     * descendant of any authorized OU.
     *
     * @throws AccessDeniedException if the DN is outside all authorized OUs
     */
    public void requireDnWithinScope(AuthPrincipal principal, UUID directoryId, String dn) {
        if (principal.isSuperadmin()) return;
        if (dn == null || dn.isBlank()) return; // null DN handled by caller

        Set<String> allowedOus = getAuthorizedOuDns(principal, directoryId);
        if (allowedOus.isEmpty()) {
            throw new AccessDeniedException("No profile access in directory [" + directoryId + "]");
        }

        String normalizedDn = dn.toLowerCase(Locale.ROOT).trim();
        boolean inScope = allowedOus.stream().anyMatch(ou -> {
            String normalizedOu = ou.toLowerCase(Locale.ROOT).trim();
            return normalizedDn.equals(normalizedOu)
                    || normalizedDn.endsWith("," + normalizedOu);
        });

        if (!inScope) {
            throw new AccessDeniedException("DN is outside authorized OUs for this admin");
        }
    }

    /**
     * Non-throwing variant of {@link #requireDnWithinScope}. Used by
     * callers that need to test scope against multiple directories
     * (e.g. the audit-log query with no directoryId filter, which
     * must accept the DN if it falls within scope of any of the
     * admin's authorized directories).
     *
     * <p>Superadmins always return {@code true}. A {@code null} or
     * blank DN returns {@code true} to mirror
     * {@link #requireDnWithinScope}'s "no-op when no DN" behaviour.</p>
     */
    public boolean isDnWithinScope(AuthPrincipal principal, UUID directoryId, String dn) {
        if (principal.isSuperadmin()) return true;
        if (dn == null || dn.isBlank()) return true;

        Set<String> allowedOus = getAuthorizedOuDns(principal, directoryId);
        if (allowedOus.isEmpty()) return false;

        String normalizedDn = dn.toLowerCase(Locale.ROOT).trim();
        return allowedOus.stream().anyMatch(ou -> {
            String normalizedOu = ou.toLowerCase(Locale.ROOT).trim();
            return normalizedDn.equals(normalizedOu)
                    || normalizedDn.endsWith("," + normalizedOu);
        });
    }

    /**
     * Validates that a search baseDn falls within the admin's authorized OUs.
     * If baseDn is null, returns {@code null} (the caller should use the
     * directory baseDn, which will be validated by the service layer).
     *
     * @throws AccessDeniedException if baseDn is outside all authorized OUs
     */
    public void requireBaseDnWithinScope(AuthPrincipal principal, UUID directoryId, String baseDn) {
        if (principal.isSuperadmin()) return;
        if (baseDn == null || baseDn.isBlank()) return; // null baseDn = use directory default

        Set<String> allowedOus = getAuthorizedOuDns(principal, directoryId);
        if (allowedOus.isEmpty()) {
            throw new AccessDeniedException("No profile access in directory [" + directoryId + "]");
        }

        String normalizedBase = baseDn.toLowerCase(Locale.ROOT).trim();
        // baseDn is valid if it equals or is a descendant of an allowed OU,
        // OR if an allowed OU is a descendant of baseDn (broader search that
        // will return results within the allowed scope)
        boolean inScope = allowedOus.stream().anyMatch(ou -> {
            String normalizedOu = ou.toLowerCase(Locale.ROOT).trim();
            return normalizedBase.equals(normalizedOu)
                    || normalizedBase.endsWith("," + normalizedOu)
                    || normalizedOu.endsWith("," + normalizedBase);
        });

        if (!inScope) {
            throw new AccessDeniedException("Search baseDn is outside authorized OUs for this admin");
        }
    }

    /**
     * Resolves the LDAP search base DN(s) to actually query for a
     * directory search request. Returns one or more bases the caller
     * should run searches against; callers fan out and merge when the
     * list has multiple entries.
     *
     * <p>Decision table:</p>
     * <ul>
     *   <li><b>Superadmin:</b> returns a single-element list containing
     *       {@code requestedBaseDn} (which may be {@code null} meaning
     *       "directory root").</li>
     *   <li><b>Admin, no requested baseDn:</b> returns the union of the
     *       admin's authorized OUs. Searching at each OU and merging
     *       gives the operator a global view of everything they're
     *       allowed to see — without exposing entries outside their
     *       profile scope (the bug the "All" profile picker option
     *       previously triggered).</li>
     *   <li><b>Admin, requested baseDn equal to or under an authorized
     *       OU:</b> returns {@code [requestedBaseDn]}. Narrower scope
     *       than the authorized OUs — safe.</li>
     *   <li><b>Admin, requested baseDn that is a strict ancestor of one
     *       or more authorized OUs:</b> returns those authorized OUs.
     *       Clamps the search to the admin's actual scope rather than
     *       passing the broader DN through (which would return entries
     *       outside scope).</li>
     *   <li><b>Admin, requested baseDn with no overlap:</b> throws
     *       {@link AccessDeniedException}.</li>
     * </ul>
     */
    public List<String> resolveSearchBaseDns(AuthPrincipal principal,
                                             UUID directoryId,
                                             String requestedBaseDn) {
        if (principal.isSuperadmin()) {
            return Collections.singletonList(requestedBaseDn);
        }

        Set<String> allowedOus = getAuthorizedOuDns(principal, directoryId);
        if (allowedOus.isEmpty()) {
            throw new AccessDeniedException(
                    "No profile access in directory [" + directoryId + "]");
        }

        if (requestedBaseDn == null || requestedBaseDn.isBlank()) {
            return List.copyOf(allowedOus);
        }

        String normalizedRequested = requestedBaseDn.toLowerCase(Locale.ROOT).trim();
        List<String> clampedToAuthorized = new ArrayList<>();
        for (String ou : allowedOus) {
            String normalizedOu = ou.toLowerCase(Locale.ROOT).trim();
            if (normalizedRequested.equals(normalizedOu)
                    || normalizedRequested.endsWith("," + normalizedOu)) {
                // Requested DN is at or under this authorized OU — narrower
                // (or equal) than the authorized scope. Use it directly.
                return Collections.singletonList(requestedBaseDn);
            }
            if (normalizedOu.endsWith("," + normalizedRequested)) {
                // Requested DN is a strict ancestor of this authorized OU —
                // clamp to the OU so the search stays within scope.
                clampedToAuthorized.add(ou);
            }
        }
        if (clampedToAuthorized.isEmpty()) {
            throw new AccessDeniedException(
                    "Search baseDn is outside authorized OUs for this admin");
        }
        return clampedToAuthorized;
    }

}
