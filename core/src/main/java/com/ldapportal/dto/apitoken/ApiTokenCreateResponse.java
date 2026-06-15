// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.apitoken;

/**
 * Response for POST {@code /api/v1/superadmin/api-tokens} and
 * POST {@code /api/v1/superadmin/api-tokens/{id}/rotate}. Carries the plaintext
 * token exactly once. Callers MUST persist it; it cannot be re-retrieved.
 */
public record ApiTokenCreateResponse(ApiTokenResponse token, String plaintext) {}
