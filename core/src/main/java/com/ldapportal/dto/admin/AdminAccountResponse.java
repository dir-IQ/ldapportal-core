// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.admin;

import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;

import java.time.Instant;
import java.util.UUID;

public record AdminAccountResponse(
        UUID id,
        String username,
        String displayName,
        String email,
        AccountRole role,
        AccountType authType,
        String ldapDn,
        boolean active,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminAccountResponse from(Account a) {
        return new AdminAccountResponse(
                a.getId(),
                a.getUsername(),
                a.getDisplayName(),
                a.getEmail(),
                a.getRole(),
                a.getAuthType(),
                a.getLdapDn(),
                a.isActive(),
                a.getLastLoginAt(),
                a.getCreatedAt(),
                a.getUpdatedAt());
    }
}
