// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.apitoken;

import com.ldapportal.entity.ApiToken;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-side representation of an API token. Never contains the plaintext or
 * the full hash — operators correlate a leaked token back to this row via
 * {@link #tokenPrefix}.
 */
public record ApiTokenResponse(
        UUID    id,
        String  name,
        String  description,
        String  tokenPrefix,
        UUID    createdById,
        String  createdByUsername,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt,
        TokenStatus status) {

    public static ApiTokenResponse from(ApiToken t) {
        return new ApiTokenResponse(
                t.getId(),
                t.getName(),
                t.getDescription(),
                t.getTokenPrefix(),
                t.getCreatedBy().getId(),
                t.getCreatedBy().getUsername(),
                t.getCreatedAt(),
                t.getExpiresAt(),
                t.getLastUsedAt(),
                t.getRevokedAt(),
                deriveStatus(t));
    }

    private static TokenStatus deriveStatus(ApiToken t) {
        if (t.getRevokedAt() != null) return TokenStatus.REVOKED;
        if (t.getExpiresAt().isBefore(Instant.now())) return TokenStatus.EXPIRED;
        return TokenStatus.ACTIVE;
    }
}
