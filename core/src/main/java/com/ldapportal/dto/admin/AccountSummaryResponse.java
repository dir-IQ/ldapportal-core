// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.admin;

import com.ldapportal.entity.Account;
import com.ldapportal.entity.enums.AccountRole;

import java.util.UUID;

/**
 * The identity of an application account — what an approver picker needs
 * and nothing more. Served to <em>every</em> superadmin, unlike
 * {@link AdminAccountResponse}, which carries contact and login details and
 * is gated by {@code VIEW_APPLICATION_ACCOUNTS}. The provisioning-profile
 * editor lists approver candidates through this so a superadmin scoped to
 * profiles can still configure database approvers.
 */
public record AccountSummaryResponse(
        UUID id,
        String username,
        String displayName,
        AccountRole role,
        boolean active) {

    public static AccountSummaryResponse from(Account a) {
        return new AccountSummaryResponse(
                a.getId(), a.getUsername(), a.getDisplayName(), a.getRole(), a.isActive());
    }
}
