// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.SuperadminPermission;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.SuperadminPermissionGrantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperadminPermissionServiceTest {

    @Mock private SuperadminPermissionGrantRepository grantRepo;
    @Mock private AccountRepository accountRepo;
    @Mock private PermissionService permissionService;
    @Mock private AuditService auditService;

    @InjectMocks private SuperadminPermissionService service;

    private final UUID targetId = UUID.randomUUID();
    private final AuthPrincipal actor =
            new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "owner");

    private Account superadminAccount() {
        Account a = new Account();
        a.setId(targetId);
        a.setUsername("scoped");
        a.setRole(AccountRole.SUPERADMIN);
        a.setActive(true);
        return a;
    }

    @Test
    void replacePermissions_refusesToDropTheLastOwner() {
        when(accountRepo.findById(targetId)).thenReturn(Optional.of(superadminAccount()));
        when(grantRepo.existsByAccountIdAndPermission(targetId, SuperadminPermission.MANAGE_SUPERADMINS))
                .thenReturn(true); // currently an owner
        when(grantRepo.countActiveAccountsWithPermission(SuperadminPermission.MANAGE_SUPERADMINS))
                .thenReturn(1L);   // ...the only one

        // Desired set drops MANAGE_SUPERADMINS → would strand the system.
        assertThatThrownBy(() -> service.replacePermissions(
                targetId, EnumSet.of(SuperadminPermission.MANAGE_APPLICATION_ACCOUNTS), actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last superadmin owner");

        verify(grantRepo, never()).deleteAllByAccountId(targetId);
    }

    @Test
    void replacePermissions_replacesGrantsForAScopedSuperadmin() {
        when(accountRepo.findById(targetId)).thenReturn(Optional.of(superadminAccount()));
        when(grantRepo.existsByAccountIdAndPermission(targetId, SuperadminPermission.MANAGE_SUPERADMINS))
                .thenReturn(false); // not an owner — no lockout concern

        var result = service.replacePermissions(
                targetId,
                EnumSet.of(SuperadminPermission.MANAGE_APPLICATION_ACCOUNTS,
                           SuperadminPermission.MANAGE_APPLICATION_SETTINGS),
                actor);

        assertThat(result).containsExactlyInAnyOrder(
                SuperadminPermission.MANAGE_APPLICATION_ACCOUNTS,
                SuperadminPermission.MANAGE_APPLICATION_SETTINGS);
        verify(grantRepo).deleteAllByAccountId(targetId);
        verify(grantRepo, org.mockito.Mockito.times(2)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void replacePermissions_rejectsNonSuperadminTarget() {
        Account admin = new Account();
        admin.setRole(AccountRole.ADMIN);
        when(accountRepo.findById(targetId)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.replacePermissions(
                targetId, EnumSet.noneOf(SuperadminPermission.class), actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a superadmin");
    }
}
