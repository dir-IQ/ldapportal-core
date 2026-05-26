// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.superadmin.CreateSuperadminRequest;
import com.ldapportal.dto.superadmin.ResetPasswordRequest;
import com.ldapportal.dto.superadmin.SuperadminResponse;
import com.ldapportal.dto.superadmin.UpdateSuperadminRequest;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SuperadminManagementService {

    private final AccountRepository accountRepo;
    private final PasswordEncoder   passwordEncoder;

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
        return SuperadminResponse.from(accountRepo.save(a));
    }

    @Transactional
    public SuperadminResponse updateSuperadmin(UUID id, UpdateSuperadminRequest req) {
        Account a = require(id);
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
        return SuperadminResponse.from(accountRepo.save(a));
    }

    @Transactional
    public void resetPassword(UUID id, ResetPasswordRequest req) {
        Account a = require(id);
        if (a.getAuthType() != AccountType.LOCAL) {
            throw new IllegalArgumentException(
                    "Password reset is only supported for LOCAL accounts");
        }
        a.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        accountRepo.save(a);
    }

    @Transactional
    public void deleteSuperadmin(UUID id) {
        Account a = require(id);
        // Guard: never delete the last active LOCAL superadmin
        if (a.getAuthType() == AccountType.LOCAL && a.isActive()
                && accountRepo.countByRoleAndAuthTypeAndActiveTrueAndIdNot(
                        AccountRole.SUPERADMIN, AccountType.LOCAL, id) == 0) {
            throw new IllegalArgumentException(
                    "Cannot delete the last active LOCAL superadmin");
        }
        accountRepo.delete(a);
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
