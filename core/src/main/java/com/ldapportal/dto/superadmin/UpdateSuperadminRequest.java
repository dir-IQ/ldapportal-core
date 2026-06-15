// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.superadmin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateSuperadminRequest(
        @Size(max = 255) String displayName,
        @Email @Size(max = 255) String email,
        boolean active) {
}
