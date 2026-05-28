// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.superadmin.CreateSuperadminRequest;
import com.ldapportal.dto.superadmin.ResetPasswordRequest;
import com.ldapportal.dto.superadmin.SuperadminResponse;
import com.ldapportal.dto.superadmin.UpdateSuperadminRequest;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SuperadminManagementService {

    private final AccountRepository accountRepo;
    private final PasswordEncoder   passwordEncoder;
    private final AuditService      auditService;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public List<SuperadminResponse> listSuperadmins() {
        return accountRepo.findAllByRole(AccountRole.SUPERADMIN).stream()
                .map(SuperadminResponse::from)
                .toList();
    }

    public SuperadminResponse getSuperadmin(UUID id) {
        return SuperadminResponse.from(require(id));
    }

    @Transactional
    public SuperadminResponse createSuperadmin(CreateSuperadminRequest req) {
        return createSuperadmin(req, null);
    }

    @Transactional
    public SuperadminResponse createSuperadmin(CreateSuperadminRequest req,
                                                AuthPrincipal principal) {
        if (accountRepo.existsByUsername(req.username())) {
            throw new ConflictException("Account [" + req.username() + "] already exists");
        }
        Account a = new Account();
        a.setUsername(req.username());
        a.setPasswordHash(passwordEncoder.encode(req.password()));
        a.setDisplayName(req.displayName());
        a.setEmail(req.email());
        a.setRole(AccountRole.SUPERADMIN);
        a.setAuthType(AccountType.LOCAL);
        a.setActive(true);
        Account saved = accountRepo.save(a);

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.ACCOUNT_CREATE,
                    Map.of("accountId", saved.getId(),
                            "username", saved.getUsername(),
                            "role", saved.getRole().name(),
                            "authType", saved.getAuthType().name()));
        }

        return SuperadminResponse.from(saved);
    }

    @Transactional
    public SuperadminResponse updateSuperadmin(UUID id, UpdateSuperadminRequest req) {
        return updateSuperadmin(id, req, null);
    }

    @Transactional
    public SuperadminResponse updateSuperadmin(UUID id, UpdateSuperadminRequest req,
                                                AuthPrincipal principal) {
        Account a = require(id);

        // Self-deactivate guard. The last-LOCAL-superadmin check below
        // catches the lock-everyone-out case, but with 2+ superadmins the
        // operator can still deactivate themselves mid-session — landing
        // on an immediately-failed page reload. Forbid it; if a
        // superadmin really needs to be deactivated, another operator
        // does it.
        if (principal != null && id.equals(principal.id()) && !req.active() && a.isActive()) {
            throw new IllegalArgumentException(
                    "Cannot deactivate your own account");
        }

        boolean activeChanged = a.isActive() != req.active();

        a.setDisplayName(req.displayName());
        a.setEmail(req.email());
        // Guard: cannot deactivate the last active LOCAL superadmin
        if (!req.active() && a.isActive()
                && a.getAuthType() == AccountType.LOCAL
                && accountRepo.countByRoleAndAuthTypeAndActiveTrueAndIdNot(
                        AccountRole.SUPERADMIN, AccountType.LOCAL, id) == 0) {
            throw new IllegalArgumentException(
                    "Cannot deactivate the last active LOCAL superadmin");
        }
        a.setActive(req.active());
        Account saved = accountRepo.save(a);

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.ACCOUNT_UPDATE,
                    Map.of("accountId", saved.getId(),
                            "username", saved.getUsername(),
                            "role", saved.getRole().name(),
                            "activeChanged", activeChanged,
                            "active", saved.isActive()));
        }

        return SuperadminResponse.from(saved);
    }

    @Transactional
    public void resetPassword(UUID id, ResetPasswordRequest req) {
        resetPassword(id, req, null);
    }

    @Transactional
    public void resetPassword(UUID id, ResetPasswordRequest req, AuthPrincipal principal) {
        Account a = require(id);
        if (a.getAuthType() != AccountType.LOCAL) {
            throw new IllegalArgumentException(
                    "Password reset is only supported for LOCAL accounts");
        }
        a.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        // Invalidate any JWT issued before this reset.
        Long current = a.getCredentialsVersion();
        a.setCredentialsVersion((current != null ? current : 0L) + 1L);
        accountRepo.save(a);

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.PASSWORD_RESET,
                    Map.of("accountId", a.getId(),
                            "username", a.getUsername(),
                            "role", a.getRole().name(),
                            "selfChange", id.equals(principal.id())));
        }
    }

    @Transactional
    public void deleteSuperadmin(UUID id) {
        deleteSuperadmin(id, null);
    }

    @Transactional
    public void deleteSuperadmin(UUID id, AuthPrincipal principal) {
        Account a = require(id);
        // Self-delete guard. Deleting your own account mid-session is
        // never the right answer — at minimum it strands the operator
        // staring at a 401 on the next click. Forbid it explicitly.
        if (principal != null && id.equals(principal.id())) {
            throw new IllegalArgumentException(
                    "Cannot delete your own account");
        }
        // Guard: never delete the last active LOCAL superadmin
        if (a.getAuthType() == AccountType.LOCAL && a.isActive()
                && accountRepo.countByRoleAndAuthTypeAndActiveTrueAndIdNot(
                        AccountRole.SUPERADMIN, AccountType.LOCAL, id) == 0) {
            throw new IllegalArgumentException(
                    "Cannot delete the last active LOCAL superadmin");
        }
        String username = a.getUsername();
        accountRepo.delete(a);

        if (principal != null) {
            auditService.recordSystemEvent(principal, AuditAction.ACCOUNT_DELETE,
                    Map.of("accountId", id,
                            "username", username,
                            "role", "SUPERADMIN"));
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Account require(UUID id) {
        Account a = accountRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
        if (a.getRole() != AccountRole.SUPERADMIN) {
            throw new ResourceNotFoundException("Account", id);
        }
        return a;
    }
}
