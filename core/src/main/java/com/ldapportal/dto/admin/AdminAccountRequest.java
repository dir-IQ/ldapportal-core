// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.admin;

import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AdminAccountRequest(
        @NotBlank @Size(max = 255) String username,
        @Size(max = 255) String displayName,
        @Email @Size(max = 255) String email,
        @NotNull AccountRole role,
        @NotNull AccountType authType,
        @Size(max = 255) String password,
        @Size(max = 1000) String ldapDn,
        boolean active) {
}
