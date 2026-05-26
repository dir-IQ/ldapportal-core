// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.AdminFeaturePermission;
import com.ldapportal.entity.AdminProfileRole;
import com.ldapportal.entity.enums.BaseRole;
import com.ldapportal.entity.enums.FeatureKey;

import com.ldapportal.repository.AdminFeaturePermissionRepository;
import com.ldapportal.repository.AdminProfileRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    @Mock private AdminProfileRoleRepository       profileRoleRepo;
    @Mock private AdminFeaturePermissionRepository featurePermissionRepo;
    @SuppressWarnings("unchecked")
    @Mock private ObjectProvider<RequestScopedPermissionCache> cacheProvider;

    private PermissionService permissionService;

    private final UUID adminId   = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();
    private final UUID dirId     = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(profileRoleRepo, featurePermissionRepo, cacheProvider);
    }

    // ── Superadmin bypass ─────────────────────────────────────────────────────

    @Test
    void requireProfileAccess_superadmin_returnsNullWithoutHittingRepo() {
        assertThat(permissionService.requireProfileAccess(superadmin(), profileId)).isNull();
        verifyNoInteractions(profileRoleRepo);
    }

    @Test
    void requireDirectoryAccess_superadmin_neverHitsRepo() {
        permissionService.requireDirectoryAccess(superadmin(), dirId);
        verifyNoInteractions(profileRoleRepo);
    }

    @Test
    void requireFeature_superadmin_neverHitsAnyRepo() {
        permissionService.requireFeature(superadmin(), dirId, FeatureKey.USER_CREATE);
        verifyNoInteractions(profileRoleRepo, featurePermissionRepo);
    }

    // ── Dimension 1+2: profile access ─────────────────────────────────────────

    @Test
    void requireProfileAccess_noRoleRow_throwsAccessDenied() {
        when(profileRoleRepo.findByAdminAccountIdAndProfileId(adminId, profileId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> permissionService.requireProfileAccess(admin(), profileId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireProfileAccess_roleExists_returnsRole() {
        AdminProfileRole role = roleFor(BaseRole.ADMIN);
        when(profileRoleRepo.findByAdminAccountIdAndProfileId(adminId, profileId))
                .thenReturn(Optional.of(role));

        assertThat(permissionService.requireProfileAccess(admin(), profileId)).isSameAs(role);
    }

    @Test
    void requireDirectoryAccess_noRoleInDirectory_throwsAccessDenied() {
        when(profileRoleRepo.existsByAdminAccountIdAndProfileDirectoryId(adminId, dirId))
                .thenReturn(false);

        assertThatThrownBy(() -> permissionService.requireDirectoryAccess(admin(), dirId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireDirectoryAccess_roleExistsInDirectory_succeeds() {
        when(profileRoleRepo.existsByAdminAccountIdAndProfileDirectoryId(adminId, dirId))
                .thenReturn(true);

        permissionService.requireDirectoryAccess(admin(), dirId);
    }

    // ── Dimension 3+4: feature overrides + per-profile overrides ──────────

    @Test
    void requireFeature_adminRole_noOverride_writeFeatureGranted() {
        when(profileRoleRepo.existsByAdminAccountIdAndProfileDirectoryId(adminId, dirId)).thenReturn(true);
        when(featurePermissionRepo.findAdminWideOverride(adminId, FeatureKey.USER_CREATE))
                .thenReturn(Optional.empty());
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryId(adminId, dirId))
                .thenReturn(List.of(roleFor(BaseRole.ADMIN)));

        permissionService.requireFeature(admin(), dirId, FeatureKey.USER_CREATE);
    }

    @Test
    void requireFeature_readOnlyRole_writeFeature_denied() {
        when(profileRoleRepo.existsByAdminAccountIdAndProfileDirectoryId(adminId, dirId)).thenReturn(true);
        when(featurePermissionRepo.findAdminWideOverride(adminId, FeatureKey.USER_DELETE))
                .thenReturn(Optional.empty());
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryId(adminId, dirId))
                .thenReturn(List.of(roleFor(BaseRole.READ_ONLY)));

        assertThatThrownBy(() -> permissionService.requireFeature(admin(), dirId, FeatureKey.USER_DELETE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireFeature_readOnlyRole_defaultReadFeature_granted() {
        when(profileRoleRepo.existsByAdminAccountIdAndProfileDirectoryId(adminId, dirId)).thenReturn(true);
        when(featurePermissionRepo.findAdminWideOverride(adminId, FeatureKey.BULK_EXPORT))
                .thenReturn(Optional.empty());
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryId(adminId, dirId))
                .thenReturn(List.of(roleFor(BaseRole.READ_ONLY)));

        permissionService.requireFeature(admin(), dirId, FeatureKey.BULK_EXPORT);
    }

    @Test
    void requireFeature_explicitEnableOverride_grantsAccessEvenForReadOnly() {
        when(profileRoleRepo.existsByAdminAccountIdAndProfileDirectoryId(adminId, dirId)).thenReturn(true);
        when(featurePermissionRepo.findAdminWideOverride(adminId, FeatureKey.USER_CREATE))
                .thenReturn(Optional.of(featureOverride(true)));
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryId(adminId, dirId))
                .thenReturn(List.of(roleFor(BaseRole.READ_ONLY)));

        permissionService.requireFeature(admin(), dirId, FeatureKey.USER_CREATE);
    }

    @Test
    void requireFeature_explicitDisableOverride_deniesEvenForAdmin() {
        when(profileRoleRepo.existsByAdminAccountIdAndProfileDirectoryId(adminId, dirId)).thenReturn(true);
        when(featurePermissionRepo.findAdminWideOverride(adminId, FeatureKey.USER_DELETE))
                .thenReturn(Optional.of(featureOverride(false)));
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryId(adminId, dirId))
                .thenReturn(List.of(roleFor(BaseRole.ADMIN)));

        assertThatThrownBy(() -> permissionService.requireFeature(admin(), dirId, FeatureKey.USER_DELETE))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireFeature_perProfileDeny_ignoredWhenAnotherProfileGrants() {
        // Most-permissive rule: deny on one profile doesn't prevent grant on another.
        when(profileRoleRepo.existsByAdminAccountIdAndProfileDirectoryId(adminId, dirId)).thenReturn(true);
        when(featurePermissionRepo.findAdminWideOverride(adminId, FeatureKey.USER_DELETE))
                .thenReturn(Optional.empty());

        AdminProfileRole denyingProfile = roleWithProfile(BaseRole.ADMIN, UUID.randomUUID());
        AdminProfileRole grantingProfile = roleWithProfile(BaseRole.ADMIN, UUID.randomUUID());
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryId(adminId, dirId))
                .thenReturn(List.of(denyingProfile, grantingProfile));
        when(featurePermissionRepo.findProfileOverride(
                adminId, denyingProfile.getProfile().getId(), FeatureKey.USER_DELETE))
                .thenReturn(Optional.of(featureOverride(false)));
        when(featurePermissionRepo.findProfileOverride(
                adminId, grantingProfile.getProfile().getId(), FeatureKey.USER_DELETE))
                .thenReturn(Optional.empty());

        permissionService.requireFeature(admin(), dirId, FeatureKey.USER_DELETE);
    }

    @Test
    void requireFeature_perProfileAllow_grantsEvenWhenBaseRoleWouldnt() {
        when(profileRoleRepo.existsByAdminAccountIdAndProfileDirectoryId(adminId, dirId)).thenReturn(true);
        when(featurePermissionRepo.findAdminWideOverride(adminId, FeatureKey.USER_DELETE))
                .thenReturn(Optional.empty());

        AdminProfileRole readOnlyProfile = roleWithProfile(BaseRole.READ_ONLY, UUID.randomUUID());
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryId(adminId, dirId))
                .thenReturn(List.of(readOnlyProfile));
        when(featurePermissionRepo.findProfileOverride(
                adminId, readOnlyProfile.getProfile().getId(), FeatureKey.USER_DELETE))
                .thenReturn(Optional.of(featureOverride(true)));

        permissionService.requireFeature(admin(), dirId, FeatureKey.USER_DELETE);
    }

    @Test
    void requireFeature_noDirectoryRole_throwsAccessDenied() {
        when(profileRoleRepo.existsByAdminAccountIdAndProfileDirectoryId(adminId, dirId))
                .thenReturn(false);

        assertThatThrownBy(() -> permissionService.requireFeature(admin(), dirId, FeatureKey.USER_CREATE))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── DN-level scoping (plan §5(j)) ─────────────────────────────────────────

    @Test
    void requireDnWithinScope_superadmin_neverHitsRepo() {
        permissionService.requireDnWithinScope(superadmin(), dirId, "cn=jdoe,ou=anywhere,dc=example,dc=com");
        verifyNoInteractions(profileRoleRepo);
    }

    @Test
    void requireDnWithinScope_dnUnderAuthorizedOu_succeeds() {
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryIdWithProfile(adminId, dirId))
                .thenReturn(List.of(roleWithOuDn(BaseRole.ADMIN, "ou=engineering,dc=example,dc=com")));

        permissionService.requireDnWithinScope(admin(), dirId, "cn=jdoe,ou=engineering,dc=example,dc=com");
    }

    @Test
    void requireDnWithinScope_dnOutsideAuthorizedOu_throwsAccessDenied() {
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryIdWithProfile(adminId, dirId))
                .thenReturn(List.of(roleWithOuDn(BaseRole.ADMIN, "ou=engineering,dc=example,dc=com")));

        assertThatThrownBy(() ->
                permissionService.requireDnWithinScope(admin(), dirId, "cn=jdoe,ou=sales,dc=example,dc=com"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("outside authorized OUs");
    }

    @Test
    void requireDnWithinScope_multipleProfiles_dnUnderAnyOu_succeeds() {
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryIdWithProfile(adminId, dirId))
                .thenReturn(List.of(
                        roleWithOuDn(BaseRole.ADMIN, "ou=engineering,dc=example,dc=com"),
                        roleWithOuDn(BaseRole.ADMIN, "ou=sales,dc=example,dc=com")));

        // Either branch should pass — admin's authorized OU set is the union.
        permissionService.requireDnWithinScope(admin(), dirId, "cn=jdoe,ou=engineering,dc=example,dc=com");
        permissionService.requireDnWithinScope(admin(), dirId, "cn=ksmith,ou=sales,dc=example,dc=com");
    }

    @Test
    void requireDnWithinScope_dnCaseDiffersFromOu_succeeds() {
        // LDAP DNs compare case-insensitively. Profile stored "ou=Engineering";
        // request DN comes in upper-case. Must still resolve as in-scope.
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryIdWithProfile(adminId, dirId))
                .thenReturn(List.of(roleWithOuDn(BaseRole.ADMIN, "ou=Engineering,dc=Example,dc=com")));

        permissionService.requireDnWithinScope(admin(), dirId, "CN=JDOE,OU=engineering,DC=example,DC=COM");
    }

    @Test
    void requireDnWithinScope_noProfileAccessInDirectory_throwsAccessDenied() {
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryIdWithProfile(adminId, dirId))
                .thenReturn(List.of());

        assertThatThrownBy(() ->
                permissionService.requireDnWithinScope(admin(), dirId, "cn=jdoe,ou=engineering,dc=example,dc=com"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("No profile access");
    }

    @Test
    void requireDnWithinScope_blankDn_isNoOp() {
        // Per the contract: null/blank DN is treated as "no DN to validate"
        // (the caller is responsible for handling that case). No repo call needed.
        permissionService.requireDnWithinScope(admin(), dirId, "");
        permissionService.requireDnWithinScope(admin(), dirId, null);
        verifyNoInteractions(profileRoleRepo);
    }

    @Test
    void requireDnWithinScope_dnEqualsAuthorizedOu_succeeds() {
        // Exact-match edge: the OU itself (not a descendant) should be in-scope.
        when(profileRoleRepo.findAllByAdminAccountIdAndProfileDirectoryIdWithProfile(adminId, dirId))
                .thenReturn(List.of(roleWithOuDn(BaseRole.ADMIN, "ou=engineering,dc=example,dc=com")));

        permissionService.requireDnWithinScope(admin(), dirId, "ou=engineering,dc=example,dc=com");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AuthPrincipal admin() {
        return new AuthPrincipal(PrincipalType.ADMIN, adminId, "alice");
    }

    private AuthPrincipal superadmin() {
        return new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "root");
    }

    private AdminProfileRole roleFor(BaseRole baseRole) {
        AdminProfileRole r = new AdminProfileRole();
        r.setBaseRole(baseRole);
        return r;
    }

    private AdminProfileRole roleWithProfile(BaseRole baseRole, UUID profileId) {
        AdminProfileRole r = new AdminProfileRole();
        r.setBaseRole(baseRole);
        com.ldapportal.entity.ProvisioningProfile profile = new com.ldapportal.entity.ProvisioningProfile();
        profile.setId(profileId);
        r.setProfile(profile);
        return r;
    }

    private AdminProfileRole roleWithOuDn(BaseRole baseRole, String targetOuDn) {
        AdminProfileRole r = new AdminProfileRole();
        r.setBaseRole(baseRole);
        com.ldapportal.entity.ProvisioningProfile profile = new com.ldapportal.entity.ProvisioningProfile();
        profile.setId(UUID.randomUUID());
        profile.setTargetOuDn(targetOuDn);
        r.setProfile(profile);
        return r;
    }

    private AdminFeaturePermission featureOverride(boolean enabled) {
        AdminFeaturePermission fp = new AdminFeaturePermission();
        fp.setEnabled(enabled);
        return fp;
    }
}
