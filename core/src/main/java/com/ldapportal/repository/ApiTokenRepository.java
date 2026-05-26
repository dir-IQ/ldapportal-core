// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ApiToken;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiTokenRepository extends JpaRepository<ApiToken, UUID> {

    @Override
    @EntityGraph(attributePaths = "createdBy")
    Optional<ApiToken> findById(UUID id);

    /** Single-row equality lookup keyed on the UNIQUE-indexed hash column. */
    @EntityGraph(attributePaths = "createdBy")
    Optional<ApiToken> findByTokenHash(byte[] tokenHash);

    /** Active-only list, newest first. Used by GET /api/v1/superadmin/api-tokens. */
    @EntityGraph(attributePaths = "createdBy")
    List<ApiToken> findAllByRevokedAtIsNullOrderByCreatedAtDesc();

    /** All tokens including revoked, newest first. Used by ?includeRevoked=true. */
    @EntityGraph(attributePaths = "createdBy")
    List<ApiToken> findAllByOrderByCreatedAtDesc();

    /**
     * Debounced last-used-at update. Plain UPDATE avoids loading the entity +
     * triggering dirty-checking + firing the JPA entity lifecycle listeners.
     */
    @Modifying
    @Query("UPDATE ApiToken t SET t.lastUsedAt = :ts WHERE t.id = :id")
    void updateLastUsedAt(@Param("id") UUID id, @Param("ts") Instant ts);
}
