// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.admin.AdminAccountRequest;
import com.ldapportal.dto.admin.AdminAccountResponse;

import com.ldapportal.dto.admin.FeaturePermissionRequest;
import com.ldapportal.dto.admin.ProfileRoleRequest;
import com.ldapportal.dto.admin.ProfileRoleResponse;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.AdminFeaturePermission;
import com.ldapportal.entity.AdminProfileRole;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ProvisioningProfile;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.entity.enums.BaseRole;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.AccountRepository;

import com.ldapportal.repository.AdminFeaturePermissionRepository;
import com.ldapportal.repository.AdminProfileRoleRepository;
import com.ldapportal.repository.ProvisioningProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminManagementServiceTest {

    @Mock private AccountRepository               accountRepo;
    @Mock private ProvisioningProfileRepository   profileRepo;
    @Mock private AdminProfileRoleRepository      profileRoleRepo;

    @Mock private AdminFeaturePermissionRepository featureRepo;
    @Mock private PasswordEncoder                 passwordEncoder;
    @Mock private com.ldapportal.core.entitlement.UsageLimitService usageLimitService;

    private AdminManagementService service;

    private final UUID adminId   = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminManagementService(
                accountRepo, profileRepo, profileRoleRepo,
                usageLimitService,
                featureRepo, passwordEncoder);
    }

    // ── listAdmins ────────────────────────────────────────────────────────────

    @Test
    void listAdmins_returnsMappedList() {
        Account a = adminAccount("alice");
        when(accountRepo.findAll()).thenReturn(List.of(a));

        List<AdminAccountResponse> result = service.listAdmins();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).username()).isEqualTo("alice");
    }

    // ── getAdmin ──────────────────────────────────────────────────────────────

    @Test
    void getAdmin_notFound_throwsResourceNotFound() {
        when(accountRepo.findById(adminId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAdmin(adminId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── createAdmin ───────────────────────────────────────────────────────────

    @Test
    void createAdmin_duplicateUsername_throwsConflict() {
        when(accountRepo.existsByUsername("bob")).thenReturn(true);

        assertThatThrownBy(() -> service.createAdmin(
                new AdminAccountRequest("bob", "Bob", "bob@e.com", AccountRole.ADMIN, AccountType.LOCAL, null, null, true)))
                .isInstanceOf(ConflictException.class);

        verify(accountRepo, never()).save(any());
    }

    @Test
    void createAdmin_success_savesAndReturns() {
        when(accountRepo.existsByUsername("alice")).thenReturn(false);
        Account saved = adminAccount("alice");
        when(accountRepo.save(any())).thenReturn(saved);

        AdminAccountResponse resp = service.createAdmin(
                new AdminAccountRequest("alice", "Alice", "a@e.com", AccountRole.ADMIN, AccountType.LOCAL, null, null, true));

        assertThat(resp.username()).isEqualTo("alice");
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepo).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(AccountRole.ADMIN);
        assertThat(captor.getValue().getAuthType()).isEqualTo(AccountType.LOCAL);
    }

    // ── assignProfileRole ─────────────────────────────────────────────────────

    @Test
    void assignProfileRole_profileNotFound_throwsResourceNotFound() {
        when(accountRepo.findById(adminId)).thenReturn(Optional.of(adminAccount("alice")));
        when(profileRepo.findById(profileId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignProfileRole(adminId,
                new ProfileRoleRequest(profileId, BaseRole.ADMIN)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void assignProfileRole_newRole_savesRole() {
        Account admin = adminAccount("alice");
        ProvisioningProfile profile = profile();
        when(accountRepo.findById(adminId)).thenReturn(Optional.of(admin));
        when(profileRepo.findById(profileId)).thenReturn(Optional.of(profile));
        when(profileRoleRepo.findByAdminAccountIdAndProfileId(adminId, profileId))
                .thenReturn(Optional.empty());

        AdminProfileRole saved = new AdminProfileRole();
        saved.setBaseRole(BaseRole.ADMIN);
        saved.setProfile(profile);
        when(profileRoleRepo.save(any())).thenReturn(saved);

        ProfileRoleResponse resp = service.assignProfileRole(adminId,
                new ProfileRoleRequest(profileId, BaseRole.ADMIN));

        assertThat(resp.baseRole()).isEqualTo(BaseRole.ADMIN);
    }

    // ── removeProfileRole ─────────────────────────────────────────────────────

    @Test
    void removeProfileRole_delegatesToRepo() {
        when(accountRepo.findById(adminId)).thenReturn(Optional.of(adminAccount("alice")));

        service.removeProfileRole(adminId, profileId);

        verify(profileRoleRepo).deleteByAdminAccountIdAndProfileId(adminId, profileId);
    }

    // ── setFeaturePermissions ─────────────────────────────────────────────────

    @Test
    void setFeaturePermissions_createsNewPermissions() {
        when(accountRepo.findById(adminId)).thenReturn(Optional.of(adminAccount("alice")));
        when(featureRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setFeaturePermissions(adminId,
                List.of(new FeaturePermissionRequest(FeatureKey.USER_CREATE, true)));

        verify(featureRepo).deleteAllByAdminAccountId(adminId);
        ArgumentCaptor<AdminFeaturePermission> captor =
                ArgumentCaptor.forClass(AdminFeaturePermission.class);
        verify(featureRepo).save(captor.capture());
        assertThat(captor.getValue().isEnabled()).isTrue();
        assertThat(captor.getValue().getFeatureKey()).isEqualTo(FeatureKey.USER_CREATE);
    }

    // ── createAdminWithPermissions ───────────────────────────────────────────

    @Test
    void createAdminWithPermissions_persistsAccountThenRolesThenFeatures() {
        when(accountRepo.existsByUsername("alice")).thenReturn(false);
        Account saved = adminAccount("alice");
        when(accountRepo.save(any())).thenReturn(saved);
        when(accountRepo.findById(adminId)).thenReturn(Optional.of(saved));
        when(profileRepo.findById(profileId)).thenReturn(Optional.of(profile()));
        when(profileRoleRepo.findByAdminAccountIdAndProfileId(adminId, profileId))
                .thenReturn(Optional.empty());
        when(profileRoleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var req = new com.ldapportal.dto.admin.CreateAdminWithPermissionsRequest(
                new AdminAccountRequest("alice", "Alice", "a@e.com",
                        AccountRole.ADMIN, AccountType.LOCAL, "pw12345678", null, true),
                List.of(new ProfileRoleRequest(profileId, BaseRole.ADMIN)),
                List.of(new FeaturePermissionRequest(FeatureKey.USER_CREATE, true, profileId)));

        AdminAccountResponse resp = service.createAdminWithPermissions(req);

        assertThat(resp.username()).isEqualTo("alice");
        verify(accountRepo, atLeastOnce()).save(any(Account.class));
        verify(profileRoleRepo).save(any(AdminProfileRole.class));
        verify(featureRepo).save(any(AdminFeaturePermission.class));
    }

    @Test
    void createAdminWithPermissions_featureForUnassignedProfile_rejected() {
        when(accountRepo.existsByUsername("alice")).thenReturn(false);
        // Throwing before save() is the goal — but createAdmin runs first
        // and saves the Account. The orchestration is @Transactional, so
        // the rollback covers the account save when we throw later.
        when(accountRepo.save(any())).thenReturn(adminAccount("alice"));

        UUID unassignedProfile = UUID.randomUUID();
        var req = new com.ldapportal.dto.admin.CreateAdminWithPermissionsRequest(
                new AdminAccountRequest("alice", "Alice", "a@e.com",
                        AccountRole.ADMIN, AccountType.LOCAL, "pw12345678", null, true),
                List.of(), // no roles
                List.of(new FeaturePermissionRequest(FeatureKey.USER_CREATE, true, unassignedProfile)));

        assertThatThrownBy(() -> service.createAdminWithPermissions(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a profile role");
        // No role / feature writes happened.
        verify(profileRoleRepo, never()).save(any());
        verify(featureRepo, never()).save(any());
    }

    @Test
    void createAdminWithPermissions_emptyListsBehavesLikePlainCreate() {
        when(accountRepo.existsByUsername("alice")).thenReturn(false);
        Account saved = adminAccount("alice");
        when(accountRepo.save(any())).thenReturn(saved);
        when(accountRepo.findById(adminId)).thenReturn(Optional.of(saved));

        var req = new com.ldapportal.dto.admin.CreateAdminWithPermissionsRequest(
                new AdminAccountRequest("alice", "Alice", "a@e.com",
                        AccountRole.ADMIN, AccountType.LOCAL, "pw12345678", null, true),
                null, null);

        AdminAccountResponse resp = service.createAdminWithPermissions(req);
        assertThat(resp.username()).isEqualTo("alice");
        verify(profileRoleRepo, never()).save(any());
        verify(featureRepo, never()).save(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Account adminAccount(String username) {
        Account a = new Account();
        a.setId(adminId);
        a.setUsername(username);
        a.setRole(AccountRole.ADMIN);
        a.setAuthType(AccountType.LOCAL);
        a.setActive(true);
        return a;
    }

    private ProvisioningProfile profile() {
        ProvisioningProfile p = new ProvisioningProfile();
        p.setId(profileId);
        p.setName("test-profile");
        DirectoryConnection dir = new DirectoryConnection();
        dir.setId(UUID.randomUUID());
        p.setDirectory(dir);
        return p;
    }
}
