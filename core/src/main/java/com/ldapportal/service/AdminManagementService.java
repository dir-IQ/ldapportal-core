// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.admin.AdminAccountRequest;
import com.ldapportal.dto.admin.AdminAccountResponse;
import com.ldapportal.dto.admin.AdminPermissionsResponse;

import com.ldapportal.dto.admin.FeaturePermissionRequest;
import com.ldapportal.dto.admin.ProfileRoleRequest;
import com.ldapportal.dto.admin.ProfileRoleResponse;
import com.ldapportal.entity.Account;

import com.ldapportal.entity.AdminFeaturePermission;
import com.ldapportal.entity.AdminProfileRole;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.AccountRepository;

import com.ldapportal.repository.AdminFeaturePermissionRepository;
import com.ldapportal.repository.AdminProfileRoleRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminManagementService {

    private final AccountRepository               accountRepo;
    private final ProvisioningProfileRepository   profileRepo;
    private final AdminProfileRoleRepository      profileRoleRepo;
    private final com.ldapportal.core.entitlement.UsageLimitService usageLimitService;

    private final AdminFeaturePermissionRepository featureRepo;
    private final PasswordEncoder                 passwordEncoder;
    private final AuditService                    auditService;

    // ── Admin account CRUD ────────────────────────────────────────────────────

    public List<AdminAccountResponse> listAdmins() {
        return accountRepo.findAll().stream()
                .map(AdminAccountResponse::from)
                .toList();
    }

    /**
     * Lists admins who have at least one profile role in the given directory.
     */
    @Transactional(readOnly = true)
    public List<AdminAccountResponse> listAdminsByDirectory(UUID directoryId) {
        return profileRoleRepo.findAllByProfileDirectoryId(directoryId).stream()
                .map(apr -> apr.getAdminAccount())
                .distinct()
                .map(AdminAccountResponse::from)
                .toList();
    }

    public AdminAccountResponse getAdmin(UUID adminId) {
        return AdminAccountResponse.from(requireAccount(adminId));
    }

    @Transactional
    public AdminAccountResponse createAdmin(AdminAccountRequest req) {
        return createAdmin(req, null);
    }

    @Transactional
    public AdminAccountResponse createAdmin(AdminAccountRequest req, AuthPrincipal principal) {
        // License cap applies only to admin-role accounts. Superadmin
        // accounts (operator / break-glass) are out of scope — they're
        // always permitted regardless of license.
        if (req.role() == AccountRole.ADMIN) {
            usageLimitService.requireWithinLimit(
                    com.ldapportal.core.entitlement.LimitType.ADMIN_ACCOUNTS,
                    accountRepo.countByRoleAndActiveTrue(AccountRole.ADMIN));
        }
        if (accountRepo.existsByUsername(req.username())) {
            throw new ConflictException("Account [" + req.username() + "] already exists");
        }
        Account a = new Account();
        a.setUsername(req.username());
        a.setDisplayName(req.displayName());
        a.setEmail(req.email());
        a.setRole(req.role());
        a.setAuthType(req.authType());
        a.setActive(req.active());
        if (req.authType() == AccountType.LOCAL && req.password() != null && !req.password().isBlank()) {
            a.setPasswordHash(passwordEncoder.encode(req.password()));
        }
        if (req.authType() == AccountType.LDAP) {
            a.setLdapDn(req.ldapDn());
        }
        // OIDC accounts need no password or ldapDn — username is matched against IdP claim
        Account saved = accountRepo.save(a);

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.ACCOUNT_CREATE,
                    Map.of("accountId", saved.getId(),
                            "username", saved.getUsername(),
                            "role", saved.getRole().name(),
                            "authType", saved.getAuthType().name(),
                            "active", saved.isActive()));
        }

        return AdminAccountResponse.from(saved);
    }

    /**
     * Creates an admin and applies its initial profile roles and feature
     * permission overrides in a single transaction. If any step fails the
     * whole thing rolls back — no half-created admin sitting in the table
     * with no permissions.
     *
     * <p>Per-profile feature overrides are only meaningful when the admin
     * has a role on that profile; this method validates that constraint
     * up-front rather than silently letting the override become inert.</p>
     */
    @Transactional
    public AdminAccountResponse createAdminWithPermissions(
            com.ldapportal.dto.admin.CreateAdminWithPermissionsRequest req) {
        return createAdminWithPermissions(req, null);
    }

    @Transactional
    public AdminAccountResponse createAdminWithPermissions(
            com.ldapportal.dto.admin.CreateAdminWithPermissionsRequest req,
            AuthPrincipal principal) {
        AdminAccountResponse created = createAdmin(req.account(), principal);

        var roles = req.profileRolesOrEmpty();
        var features = req.featurePermissionsOrEmpty();

        // Per-profile feature overrides require the admin to hold a role
        // on that profile. The orchestrated create lets us catch the
        // mismatch up-front instead of silently storing inert override
        // rows.
        java.util.Set<UUID> assignedProfileIds = roles.stream()
                .map(ProfileRoleRequest::profileId)
                .collect(java.util.stream.Collectors.toSet());
        for (FeaturePermissionRequest fp : features) {
            if (fp.profileId() != null && !assignedProfileIds.contains(fp.profileId())) {
                throw new IllegalArgumentException(
                        "Feature permission for profile [" + fp.profileId()
                                + "] requires a profile role on the same profile");
            }
        }

        for (ProfileRoleRequest r : roles) {
            assignProfileRole(created.id(), r, principal);
        }
        if (!features.isEmpty()) {
            setFeaturePermissions(created.id(), features, principal);
        }

        return AdminAccountResponse.from(requireAccount(created.id()));
    }

    @Transactional
    public AdminAccountResponse updateAdmin(UUID adminId, AdminAccountRequest req) {
        return updateAdmin(adminId, req, null);
    }

    @Transactional
    public AdminAccountResponse updateAdmin(UUID adminId, AdminAccountRequest req,
                                             AuthPrincipal principal) {
        Account a = requireAccount(adminId);

        // Self-mutation guard. After requireAccount tightens to ADMIN-only,
        // a SUPERADMIN principal's id can't match an ADMIN row id anyway,
        // but we assert defensively for clarity and to catch any future
        // role overlap.
        if (principal != null && adminId.equals(principal.id())) {
            throw new IllegalArgumentException(
                    "Cannot modify your own account through this endpoint");
        }

        // Role is immutable through this endpoint. Promotion to SUPERADMIN
        // (or demotion away from ADMIN) is sensitive enough to belong on a
        // dedicated path with its own guards. Closes the license-cap bypass
        // where role changes silently sidestepped the create-time check.
        if (req.role() != a.getRole()) {
            throw new IllegalArgumentException(
                    "Role cannot be changed via this endpoint");
        }

        // License cap re-check on activate: an inactive admin doesn't count
        // toward the cap; activating it does, so an org at-limit can't
        // re-light an inactive row to slip past the cap.
        if (req.active() && !a.isActive()) {
            usageLimitService.requireWithinLimit(
                    com.ldapportal.core.entitlement.LimitType.ADMIN_ACCOUNTS,
                    accountRepo.countByRoleAndActiveTrue(AccountRole.ADMIN));
        }

        if (!a.getUsername().equals(req.username()) && accountRepo.existsByUsername(req.username())) {
            throw new ConflictException("Account [" + req.username() + "] already exists");
        }
        // Detect credential-changing edits so we can bump credentialsVersion
        // and invalidate any JWT issued before the change. authType-switch
        // also clears the password hash; both are credential-affecting.
        boolean authTypeChanged = a.getAuthType() != req.authType();
        boolean passwordSet = req.authType() == AccountType.LOCAL
                && req.password() != null && !req.password().isBlank();

        a.setUsername(req.username());
        a.setDisplayName(req.displayName());
        a.setEmail(req.email());
        // a.setRole(req.role()) intentionally omitted — role is immutable here.
        a.setAuthType(req.authType());
        a.setActive(req.active());
        if (passwordSet) {
            a.setPasswordHash(passwordEncoder.encode(req.password()));
        }
        if (req.authType() == AccountType.LDAP) {
            a.setLdapDn(req.ldapDn());
        } else {
            a.setLdapDn(null);
        }
        // Clear password hash when switching away from LOCAL
        if (req.authType() != AccountType.LOCAL) {
            a.setPasswordHash(null);
        }
        if (authTypeChanged || passwordSet) {
            bumpCredentialsVersion(a);
        }
        Account saved = accountRepo.save(a);

        if (principal != null) {
            List<String> changed = new ArrayList<>();
            if (authTypeChanged) changed.add("authType");
            if (passwordSet) changed.add("password");
            // The other fields are always assigned from req; we don't try to
            // compute a precise diff. The audit log carries the actor and
            // timestamp, and the new state is in the response.
            auditService.recordSystemEvent(principal, AuditAction.ACCOUNT_UPDATE,
                    Map.of("accountId", saved.getId(),
                            "username", saved.getUsername(),
                            "role", saved.getRole().name(),
                            "credentialFieldsChanged", changed));
        }

        return AdminAccountResponse.from(saved);
    }

    @Transactional
    public void resetAdminPassword(UUID adminId, String newPassword) {
        resetAdminPassword(adminId, newPassword, null);
    }

    @Transactional
    public void resetAdminPassword(UUID adminId, String newPassword, AuthPrincipal principal) {
        Account a = requireAccount(adminId);
        if (a.getAuthType() != AccountType.LOCAL) {
            throw new IllegalArgumentException("Password reset is only supported for LOCAL accounts");
        }
        a.setPasswordHash(passwordEncoder.encode(newPassword));
        bumpCredentialsVersion(a);
        accountRepo.save(a);

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.PASSWORD_RESET,
                    Map.of("accountId", a.getId(),
                            "username", a.getUsername(),
                            "role", a.getRole().name(),
                            "selfChange", false));
        }
    }

    /**
     * Bumps the account's credentials-version counter so any JWT issued
     * before this point fails the next request. Called by every
     * credential-changing op (password set/reset, authType switch).
     */
    private void bumpCredentialsVersion(Account a) {
        Long current = a.getCredentialsVersion();
        a.setCredentialsVersion((current != null ? current : 0L) + 1L);
    }

    @Transactional
    public void deleteAdmin(UUID adminId) {
        deleteAdmin(adminId, null);
    }

    @Transactional
    public void deleteAdmin(UUID adminId, AuthPrincipal principal) {
        if (principal != null && adminId.equals(principal.id())) {
            throw new IllegalArgumentException(
                    "Cannot delete your own account through this endpoint");
        }
        Account a = requireAccount(adminId);
        String username = a.getUsername();
        AccountRole role = a.getRole();
        accountRepo.delete(a);

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.ACCOUNT_DELETE,
                    Map.of("accountId", adminId,
                            "username", username,
                            "role", role.name()));
        }
    }

    // ── Permission management — summary ───────────────────────────────────────

    @Transactional(readOnly = true)
    public AdminPermissionsResponse getPermissions(UUID adminId) {
        requireAccount(adminId);
        return AdminPermissionsResponse.from(
                profileRoleRepo.findAllByAdminAccountId(adminId),
                featureRepo.findAllByAdminAccountId(adminId));
    }

    // ── Dimension 1+2: profile roles ──────────────────────────────────────────

    @Transactional
    public ProfileRoleResponse assignProfileRole(UUID adminId, ProfileRoleRequest req) {
        return assignProfileRole(adminId, req, null);
    }

    @Transactional
    public ProfileRoleResponse assignProfileRole(UUID adminId, ProfileRoleRequest req,
                                                  AuthPrincipal principal) {
        Account admin = requireAccount(adminId);
        ProvisioningProfile profile = requireProfile(req.profileId());

        AdminProfileRole role = profileRoleRepo
                .findByAdminAccountIdAndProfileId(adminId, req.profileId())
                .orElseGet(AdminProfileRole::new);

        boolean isNew = role.getId() == null;
        if (isNew) {
            role.setAdminAccount(admin);
            role.setProfile(profile);
        }
        role.setBaseRole(req.baseRole());
        AdminProfileRole saved = profileRoleRepo.save(role);

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.ACCOUNT_PERMISSION_CHANGED,
                    Map.of("accountId", adminId,
                            "username", admin.getUsername(),
                            "op", isNew ? "assign_profile_role" : "update_profile_role",
                            "profileId", req.profileId(),
                            "baseRole", req.baseRole().name()));
        }

        return ProfileRoleResponse.from(saved);
    }

    @Transactional
    public void removeProfileRole(UUID adminId, UUID profileId) {
        removeProfileRole(adminId, profileId, null);
    }

    @Transactional
    public void removeProfileRole(UUID adminId, UUID profileId, AuthPrincipal principal) {
        Account admin = requireAccount(adminId);
        profileRoleRepo.deleteByAdminAccountIdAndProfileId(adminId, profileId);

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.ACCOUNT_PERMISSION_CHANGED,
                    Map.of("accountId", adminId,
                            "username", admin.getUsername(),
                            "op", "remove_profile_role",
                            "profileId", profileId));
        }
    }

    // ── Dimension 4: feature permissions ─────────────────────────────────────

    @Transactional
    public void setFeaturePermissions(UUID adminId, List<FeaturePermissionRequest> permissions) {
        setFeaturePermissions(adminId, permissions, null);
    }

    @Transactional
    public void setFeaturePermissions(UUID adminId, List<FeaturePermissionRequest> permissions,
                                       AuthPrincipal principal) {
        Account admin = requireAccount(adminId);

        // Replace every override (admin-wide and per-profile) with the list
        // the caller sent. The permissions dialog loads then re-submits the
        // whole set, so wipe-and-recreate keeps client and server in sync
        // without needing patch semantics for delete/update/insert.
        featureRepo.deleteAllByAdminAccountId(adminId);
        featureRepo.flush();

        permissions.forEach(req -> {
            AdminFeaturePermission fp = new AdminFeaturePermission();
            fp.setAdminAccount(admin);
            fp.setFeatureKey(req.featureKey());
            fp.setEnabled(req.enabled());
            if (req.profileId() != null) {
                ProvisioningProfile profileRef = profileRepo.findById(req.profileId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "ProvisioningProfile", req.profileId()));
                fp.setProfile(profileRef);
            }
            featureRepo.save(fp);
        });

        if (principal != null) {
            // The wipe-and-recreate model means one audit row covers the
            // whole new override set. Include the count and the keys so the
            // log carries useful detail without exploding the row.
            List<Map<String, Object>> summary = new ArrayList<>();
            for (FeaturePermissionRequest p : permissions) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("featureKey", p.featureKey().name());
                entry.put("enabled", p.enabled());
                if (p.profileId() != null) entry.put("profileId", p.profileId());
                summary.add(entry);
            }
            auditService.recordSystemEvent(principal, AuditAction.ACCOUNT_PERMISSION_CHANGED,
                    Map.of("accountId", adminId,
                            "username", admin.getUsername(),
                            "op", "set_feature_permissions",
                            "count", permissions.size(),
                            "overrides", summary));
        }
    }

    @Transactional
    public void clearFeaturePermission(UUID adminId, FeatureKey featureKey) {
        clearFeaturePermission(adminId, featureKey, null);
    }

    @Transactional
    public void clearFeaturePermission(UUID adminId, FeatureKey featureKey,
                                        AuthPrincipal principal) {
        Account admin = requireAccount(adminId);
        // Clears the admin-wide override only. Per-profile overrides are
        // addressed by clearProfileFeaturePermission below.
        featureRepo.deleteAdminWideOverride(adminId, featureKey);

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.ACCOUNT_PERMISSION_CHANGED,
                    Map.of("accountId", adminId,
                            "username", admin.getUsername(),
                            "op", "clear_admin_wide_feature",
                            "featureKey", featureKey.name()));
        }
    }

    @Transactional
    public void clearProfileFeaturePermission(UUID adminId, UUID profileId, FeatureKey featureKey) {
        clearProfileFeaturePermission(adminId, profileId, featureKey, null);
    }

    @Transactional
    public void clearProfileFeaturePermission(UUID adminId, UUID profileId, FeatureKey featureKey,
                                                AuthPrincipal principal) {
        Account admin = requireAccount(adminId);
        featureRepo.deleteProfileOverride(adminId, profileId, featureKey);

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.ACCOUNT_PERMISSION_CHANGED,
                    Map.of("accountId", adminId,
                            "username", admin.getUsername(),
                            "op", "clear_profile_feature",
                            "profileId", profileId,
                            "featureKey", featureKey.name()));
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves an admin row by id. Critically, this is the <em>only</em>
     * lookup used by every mutating method on this service, and it 404s
     * on anything other than {@link AccountRole#ADMIN}. That closes the
     * cross-endpoint bypass where {@code PUT /superadmin/admins/{id}} or
     * {@code DELETE /superadmin/admins/{id}} would otherwise act on a
     * superadmin row (skipping the last-LOCAL-superadmin and self-delete
     * guards that live on {@link SuperadminManagementService}).
     */
    private Account requireAccount(UUID adminId) {
        Account a = accountRepo.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", adminId));
        if (a.getRole() != AccountRole.ADMIN) {
            throw new ResourceNotFoundException("Account", adminId);
        }
        return a;
    }

    private ProvisioningProfile requireProfile(UUID profileId) {
        return profileRepo.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", profileId));
    }
}
