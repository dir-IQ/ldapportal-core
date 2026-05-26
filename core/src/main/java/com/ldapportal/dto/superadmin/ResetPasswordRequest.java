// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.superadmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(@NotBlank @Size(min = 8) String newPassword) {
}
