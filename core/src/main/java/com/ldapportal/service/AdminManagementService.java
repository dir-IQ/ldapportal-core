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

import java.util.List;
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
        return AdminAccountResponse.from(accountRepo.save(a));
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
        AdminAccountResponse created = createAdmin(req.account());

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
            assignProfileRole(created.id(), r);
        }
        if (!features.isEmpty()) {
            setFeaturePermissions(created.id(), features);
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
        a.setUsername(req.username());
        a.setDisplayName(req.displayName());
        a.setEmail(req.email());
        // a.setRole(req.role()) intentionally omitted — role is immutable here.
        a.setAuthType(req.authType());
        a.setActive(req.active());
        if (req.authType() == AccountType.LOCAL && req.password() != null && !req.password().isBlank()) {
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
        return AdminAccountResponse.from(accountRepo.save(a));
    }

    @Transactional
    public void resetAdminPassword(UUID adminId, String newPassword) {
        Account a = requireAccount(adminId);
        if (a.getAuthType() != AccountType.LOCAL) {
            throw new IllegalArgumentException("Password reset is only supported for LOCAL accounts");
        }
        a.setPasswordHash(passwordEncoder.encode(newPassword));
        accountRepo.save(a);
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
        accountRepo.delete(requireAccount(adminId));
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
        Account admin = requireAccount(adminId);
        ProvisioningProfile profile = requireProfile(req.profileId());

        AdminProfileRole role = profileRoleRepo
                .findByAdminAccountIdAndProfileId(adminId, req.profileId())
                .orElseGet(AdminProfileRole::new);

        if (role.getId() == null) {
            role.setAdminAccount(admin);
            role.setProfile(profile);
        }
        role.setBaseRole(req.baseRole());
        return ProfileRoleResponse.from(profileRoleRepo.save(role));
    }

    @Transactional
    public void removeProfileRole(UUID adminId, UUID profileId) {
        requireAccount(adminId);
        profileRoleRepo.deleteByAdminAccountIdAndProfileId(adminId, profileId);
    }

    // ── Dimension 4: feature permissions ─────────────────────────────────────

    @Transactional
    public void setFeaturePermissions(UUID adminId, List<FeaturePermissionRequest> permissions) {
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
    }

    @Transactional
    public void clearFeaturePermission(UUID adminId, FeatureKey featureKey) {
        requireAccount(adminId);
        // Clears the admin-wide override only. Per-profile overrides are
        // addressed by clearProfileFeaturePermission below.
        featureRepo.deleteAdminWideOverride(adminId, featureKey);
    }

    @Transactional
    public void clearProfileFeaturePermission(UUID adminId, UUID profileId, FeatureKey featureKey) {
        requireAccount(adminId);
        featureRepo.deleteProfileOverride(adminId, profileId, featureKey);
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
