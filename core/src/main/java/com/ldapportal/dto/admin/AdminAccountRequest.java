// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.admin;

import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body shared by create and update on /superadmin/admins. {@code active}
 * is {@link Boolean} (not primitive) so a request body that omits the
 * field doesn't silently default to {@code false} — which used to mean
 * "any client that forgot to send {@code active:true} created a disabled
 * admin." Callers that intend {@code false} must send it explicitly;
 * {@code null} now means "default for the operation," which create
 * resolves to {@code true} and update resolves to "preserve current."
 */
public record AdminAccountRequest(
        @NotBlank @Size(max = 255) String username,
        @Size(max = 255) String displayName,
        @Email @Size(max = 255) String email,
        @NotNull AccountRole role,
        @NotNull AccountType authType,
        @Size(max = 255) String password,
        @Size(max = 1000) String ldapDn,
        Boolean active) {

    /** Effective active flag for create — defaults to true when omitted. */
    public boolean activeForCreate() {
        return active == null ? true : active;
    }
}
