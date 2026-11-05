// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.settings;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/settings/smtp/test}: where to send the test message. */
public record SmtpTestRequest(
        @NotBlank @Email @Size(max = 255) String recipient) {
}
