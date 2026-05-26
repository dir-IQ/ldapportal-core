// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Long-lived API token for machine authentication (Terraform, webhooks, CI).
 *
 * <p>The plaintext is returned to the creator exactly once (at POST) and never
 * stored. {@link #tokenHash} carries the SHA-256 digest of the plaintext,
 * {@link #tokenPrefix} carries the first 16 characters for operator
 * correlation.</p>
 *
 * <p>See {@code docs/superpowers/specs/2026-04-22-api-token-auth-design.md}.</p>
 */
@Entity
@Table(name = "api_tokens")
@Getter
@Setter
@NoArgsConstructor
public class ApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "token_hash", nullable = false)
    private byte[] tokenHash;

    @Column(name = "token_prefix", nullable = false, length = 32)
    private String tokenPrefix;

    /** Forward-compat; v1 always null. Stored as raw JSONB string. */
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String scopes;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private Account createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * {@code true} when the token is neither revoked nor past its expiry.
     * Used by the authentication path to gate valid-but-active checks.
     */
    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
