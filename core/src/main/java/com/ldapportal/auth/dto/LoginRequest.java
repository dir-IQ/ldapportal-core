// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login request body.
 */
public record LoginRequest(
        @NotBlank String username,
        @NotBlank String password) {
}
