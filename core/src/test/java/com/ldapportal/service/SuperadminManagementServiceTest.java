// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.superadmin.CreateSuperadminRequest;
import com.ldapportal.dto.superadmin.ResetPasswordRequest;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuperadminManagementServiceTest {

    @Mock private AccountRepository           repo;
    @Mock private AuditService                auditService;
    @Mock private ApprovalNotificationService notificationService;

    private PasswordEncoder             encoder = new BCryptPasswordEncoder();
    private SuperadminManagementService service;

    @BeforeEach
    void setUp() {
        service = new SuperadminManagementService(repo, encoder, auditService, notificationService);
    }

    @Test
    void createSuperadmin_encodesPassword() {
        when(repo.existsByUsername("alice")).thenReturn(false);
        when(repo.save(any(Account.class))).thenAnswer(inv -> {
            Account a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        service.createSuperadmin(new CreateSuperadminRequest("alice", "Str0ng-pw-2026!", null, null));

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(repo).save(captor.capture());
        assertThat(encoder.matches("Str0ng-pw-2026!", captor.getValue().getPasswordHash())).isTrue();
        assertThat(captor.getValue().getAuthType()).isEqualTo(AccountType.LOCAL);
        assertThat(captor.getValue().getRole()).isEqualTo(AccountRole.SUPERADMIN);
    }

    @Test
    void createSuperadmin_duplicateUsername_throwsConflict() {
        when(repo.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() ->
                service.createSuperadmin(new CreateSuperadminRequest("alice", "pass1234", null, null)))
                .isInstanceOf(ConflictException.class);

        verify(repo, never()).save(any());
    }

    @Test
    void deleteSuperadmin_lastLocalActive_throws() {
        UUID id = UUID.randomUUID();
        Account a = localAccount(id, true);
        when(repo.findById(id)).thenReturn(Optional.of(a));
        // Pretend another non-LOCAL superadmin is active so the broader
        // "at least one active superadmin" guard doesn't fire — we want
        // to land specifically in the LOCAL-break-glass branch.
        when(repo.countByRoleAndActiveTrueAndIdNot(AccountRole.SUPERADMIN, id))
                .thenReturn(1L);
        when(repo.countByRoleAndAuthTypeAndActiveTrueAndIdNot(AccountRole.SUPERADMIN, AccountType.LOCAL, id))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.deleteSuperadmin(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last active LOCAL");
    }

    @Test
    void resetPassword_ldapAccount_throws() {
        UUID id = UUID.randomUUID();
        Account a = new Account();
        a.setId(id);
        a.setRole(AccountRole.SUPERADMIN);
        a.setAuthType(AccountType.LDAP);
        when(repo.findById(id)).thenReturn(Optional.of(a));

        assertThatThrownBy(() -> service.resetPassword(id, new ResetPasswordRequest("newpass1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getSuperadmin_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSuperadmin(id))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── Self-mutation guards ─────────────────────────────────────────────────

    @Test
    void deleteSuperadmin_selfDelete_rejected() {
        UUID id = UUID.randomUUID();
        Account self = localAccount(id, true);
        when(repo.findById(id)).thenReturn(Optional.of(self));
        var principal = new com.ldapportal.auth.AuthPrincipal(
                com.ldapportal.auth.PrincipalType.SUPERADMIN, id, "admin");

        assertThatThrownBy(() -> service.deleteSuperadmin(id, principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot delete your own account");
        verify(repo, never()).delete(any());
    }

    @Test
    void updateSuperadmin_selfDeactivate_rejected() {
        UUID id = UUID.randomUUID();
        Account self = localAccount(id, true);
        when(repo.findById(id)).thenReturn(Optional.of(self));
        var principal = new com.ldapportal.auth.AuthPrincipal(
                com.ldapportal.auth.PrincipalType.SUPERADMIN, id, "admin");

        var req = new com.ldapportal.dto.superadmin.UpdateSuperadminRequest(
                "Admin", null, false);

        assertThatThrownBy(() -> service.updateSuperadmin(id, req, principal))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot deactivate your own account");
        verify(repo, never()).save(any());
    }

    @Test
    void updateSuperadmin_deactivateAnotherSuperadmin_succeeds() {
        UUID self = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Account otherAcct = localAccount(other, true);
        when(repo.findById(other)).thenReturn(Optional.of(otherAcct));
        // Last-active-superadmin invariant: pretend at least one other
        // active superadmin exists so neither guard fires.
        when(repo.countByRoleAndActiveTrueAndIdNot(AccountRole.SUPERADMIN, other))
                .thenReturn(1L);
        // Last-LOCAL-superadmin guard: pretend there's another LOCAL
        // superadmin so we're not at count==0.
        when(repo.countByRoleAndAuthTypeAndActiveTrueAndIdNot(
                AccountRole.SUPERADMIN, AccountType.LOCAL, other))
                .thenReturn(1L);
        when(repo.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        var principal = new com.ldapportal.auth.AuthPrincipal(
                com.ldapportal.auth.PrincipalType.SUPERADMIN, self, "self");

        var req = new com.ldapportal.dto.superadmin.UpdateSuperadminRequest(
                "Admin", null, false);

        service.updateSuperadmin(other, req, principal);
        verify(repo).save(any());
    }

    @Test
    void deleteSuperadmin_lastActiveSuperadminAnyType_rejected() {
        // Belt-and-suspenders invariant: refuse to delete the last
        // active SUPERADMIN even if it isn't a LOCAL row (edge case
        // where an org never had a LOCAL).
        UUID id = UUID.randomUUID();
        Account a = localAccount(id, true);
        a.setAuthType(AccountType.OIDC); // skip the LOCAL guard branch
        when(repo.findById(id)).thenReturn(Optional.of(a));
        when(repo.countByRoleAndActiveTrueAndIdNot(AccountRole.SUPERADMIN, id))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.deleteSuperadmin(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last active superadmin");
        verify(repo, never()).delete(any());
    }

    @Test
    void updateSuperadmin_lastActiveSuperadminAnyType_deactivateRejected() {
        UUID id = UUID.randomUUID();
        Account a = localAccount(id, true);
        a.setAuthType(AccountType.OIDC);
        when(repo.findById(id)).thenReturn(Optional.of(a));
        when(repo.countByRoleAndActiveTrueAndIdNot(AccountRole.SUPERADMIN, id))
                .thenReturn(0L);

        var req = new com.ldapportal.dto.superadmin.UpdateSuperadminRequest(
                "Admin", null, false);

        assertThatThrownBy(() -> service.updateSuperadmin(id, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("last active superadmin");
        verify(repo, never()).save(any());
    }

    @Test
    void resetPassword_otherOperator_notifiesTarget() {
        UUID targetId = UUID.randomUUID();
        UUID actorId  = UUID.randomUUID();
        Account target = localAccount(targetId, true);
        target.setEmail("alice@example.com");
        when(repo.findById(targetId)).thenReturn(Optional.of(target));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var principal = new com.ldapportal.auth.AuthPrincipal(
                com.ldapportal.auth.PrincipalType.SUPERADMIN, actorId, "operator");

        service.resetPassword(targetId,
                new com.ldapportal.dto.superadmin.ResetPasswordRequest("Str0ng-pw-2026!"),
                principal);

        verify(notificationService).notifyPasswordReset(eq(target), eq(principal));
    }

    @Test
    void resetPassword_selfChange_skipsNotification() {
        UUID id = UUID.randomUUID();
        Account self = localAccount(id, true);
        self.setEmail("alice@example.com");
        when(repo.findById(id)).thenReturn(Optional.of(self));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var principal = new com.ldapportal.auth.AuthPrincipal(
                com.ldapportal.auth.PrincipalType.SUPERADMIN, id, "alice");

        service.resetPassword(id,
                new com.ldapportal.dto.superadmin.ResetPasswordRequest("Str0ng-pw-2026!"),
                principal);

        verify(notificationService, never()).notifyPasswordReset(any(), any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Account localAccount(UUID id, boolean active) {
        Account a = new Account();
        a.setId(id);
        a.setUsername("admin");
        a.setRole(AccountRole.SUPERADMIN);
        a.setAuthType(AccountType.LOCAL);
        a.setPasswordHash(encoder.encode("secret"));
        a.setActive(active);
        return a;
    }
}
