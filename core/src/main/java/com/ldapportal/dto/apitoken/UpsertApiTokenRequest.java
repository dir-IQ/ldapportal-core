// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.apitoken;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Payload for the idempotent by-name upsert
 * {@code PUT /api/v1/superadmin/api-tokens/by-name/{name}}. The name is the
 * stable IaC key and comes from the path, so it isn't repeated here; only the
 * mutable metadata is. Maximum-expiry enforcement (2 years) lives in
 * {@link com.ldapportal.auth.ApiTokenService} because the upper bound is
 * relative to "now."
 *
 * <p>The token secret is never set or rotated through this endpoint — it is
 * minted exactly once, when the named token is first created. Rotating an
 * existing token's secret stays an explicit verb
 * ({@code POST /api/v1/superadmin/api-tokens/{id}/rotate}).</p>
 */
public record UpsertApiTokenRequest(
        @Size(max = 500) String description,
        @NotNull @Future  Instant expiresAt) {}
