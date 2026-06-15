// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.superadmin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSuperadminRequest(
        @NotBlank @Size(max = 255) String username,
        @NotBlank @Size(min = 8) String password,
        @Size(max = 255) String displayName,
        @Email @Size(max = 255) String email) {
}
