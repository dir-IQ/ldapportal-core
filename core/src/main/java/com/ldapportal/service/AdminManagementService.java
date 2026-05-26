// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

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

    @Transactional
    public AdminAccountResponse updateAdmin(UUID adminId, AdminAccountRequest req) {
        Account a = requireAccount(adminId);
        if (!a.getUsername().equals(req.username()) && accountRepo.existsByUsername(req.username())) {
            throw new ConflictException("Account [" + req.username() + "] already exists");
        }
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

    private Account requireAccount(UUID adminId) {
        return accountRepo.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", adminId));
    }

    private ProvisioningProfile requireProfile(UUID profileId) {
        return profileRepo.findById(profileId)
                .orElseThrow(() -> new ResourceNotFoundException("ProvisioningProfile", profileId));
    }
}
