// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.entity.Account;
import com.ldapportal.entity.SuperadminPermissionGrant;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.SuperadminPermission;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.repository.SuperadminPermissionGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Manages the system-scoped {@link SuperadminPermission} grants held by
 * superadmin accounts. Read helpers feed the {@code /me} payload and the
 * permission editor; {@link #replacePermissions} applies an owner's edits with
 * the no-lockout invariant.
 *
 * <p>Editing is restricted to owners by the {@code MANAGE_SUPERADMINS} gate on
 * the controller; this service enforces the data-integrity invariant
 * (never strand the system without an owner).</p>
 */
@Service
@RequiredArgsConstructor
public class SuperadminPermissionService {

    private final SuperadminPermissionGrantRepository grantRepo;
    private final AccountRepository accountRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;

    /** Permissions actually granted to the account (no owner expansion). */
    @Transactional(readOnly = true)
    public Set<SuperadminPermission> granted(UUID accountId) {
        return grantRepo.findAllByAccountId(accountId).stream()
                .map(SuperadminPermissionGrant::getPermission)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(SuperadminPermission.class)));
    }

    /** Effective permissions — granted, expanded to all when the account is an owner. */
    @Transactional(readOnly = true)
    public Set<SuperadminPermission> effective(UUID accountId) {
        return permissionService.effectiveSuperadminPermissions(accountId);
    }

    /**
     * Replace a superadmin's granted permission set with {@code desired}.
     * Refuses to remove {@link SuperadminPermission#MANAGE_SUPERADMINS} from the
     * last remaining owner (lockout guard).
     *
     * @return the normalized set actually stored
     */
    @Transactional
    public Set<SuperadminPermission> replacePermissions(UUID targetId,
                                                        Set<SuperadminPermission> desired,
                                                        AuthPrincipal actor) {
        Account target = accountRepo.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Superadmin not found: " + targetId));
        if (target.getRole() != AccountRole.SUPERADMIN) {
            throw new IllegalArgumentException("Account is not a superadmin");
        }

        Set<SuperadminPermission> normalized = desired.isEmpty()
                ? EnumSet.noneOf(SuperadminPermission.class)
                : EnumSet.copyOf(desired);

        boolean wasOwner = grantRepo.existsByAccountIdAndPermission(
                targetId, SuperadminPermission.MANAGE_SUPERADMINS);
        boolean willBeOwner = normalized.contains(SuperadminPermission.MANAGE_SUPERADMINS);
        if (wasOwner && !willBeOwner
                && grantRepo.countActiveAccountsWithPermission(
                        SuperadminPermission.MANAGE_SUPERADMINS) <= 1) {
            throw new IllegalArgumentException(
                    "Cannot remove the last superadmin owner (Manage Superadmins) — "
                            + "grant another superadmin the owner permission first.");
        }

        grantRepo.deleteAllByAccountId(targetId);
        grantRepo.flush(); // ensure deletes land before re-inserts (unique constraint)
        for (SuperadminPermission p : normalized) {
            SuperadminPermissionGrant grant = new SuperadminPermissionGrant();
            grant.setAccount(target);
            grant.setPermission(p);
            grantRepo.save(grant);
        }

        auditService.recordSystemEvent(actor, AuditAction.ACCOUNT_UPDATE,
                Map.of("accountId", target.getId(),
                        "username", target.getUsername(),
                        "detail", "superadmin_permissions_update",
                        "permissions", normalized.stream()
                                .map(SuperadminPermission::getDbValue).sorted().toList()));

        return normalized;
    }
}
