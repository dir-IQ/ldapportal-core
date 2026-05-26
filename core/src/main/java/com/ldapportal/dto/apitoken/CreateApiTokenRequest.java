// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.apitoken;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Payload for POST {@code /api/v1/superadmin/api-tokens}.
 * Maximum-expiry enforcement (2 years) is in {@link com.ldapportal.auth.ApiTokenService}
 * because the upper bound is relative to "now."
 */
public record CreateApiTokenRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500)            String description,
        @NotNull @Future            Instant expiresAt) {}
