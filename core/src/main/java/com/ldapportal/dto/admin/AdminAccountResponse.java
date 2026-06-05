// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.admin;

import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;

import java.time.Instant;
import java.util.UUID;

public record AdminAccountResponse(
        UUID id,
        Long version,
        String username,
        String displayName,
        String email,
        AccountRole role,
        AccountType authType,
        String ldapDn,
        boolean active,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt,
        // ── Write-only secret presence (§4.3) ───────────────────────────────
        // The password hash is never returned; this lets an idempotent
        // applier tell whether a LOCAL account already has a password set
        // (so it can omit it on update) without exposing the credential.
        boolean passwordSet) {

    public static AdminAccountResponse from(Account a) {
        return new AdminAccountResponse(
                a.getId(),
                a.getVersion(),
                a.getUsername(),
                a.getDisplayName(),
                a.getEmail(),
                a.getRole(),
                a.getAuthType(),
                a.getLdapDn(),
                a.isActive(),
                a.getLastLoginAt(),
                a.getCreatedAt(),
                a.getUpdatedAt(),
                a.getPasswordHash() != null && !a.getPasswordHash().isBlank());
    }
}
