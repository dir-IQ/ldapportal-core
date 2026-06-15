// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.FeatureKey;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Permission model Dimension 4 (§3.2).
 * <p>
 * Per-feature enable/disable overrides for an admin account.  A row here
 * overrides the capability that the admin's base role would normally grant
 * or deny.  The {@link FeatureKey} enum constants are stored as
 * dot-notation strings via
 * {@link com.ldapportal.entity.converter.FeatureKeyConverter}.
 *
 * <h3>Scope — admin-wide vs per-profile</h3>
 * <p>
 * When {@link #profile} is {@code null} the override applies across every
 * profile the admin has access to (admin-wide); when set, the override
 * applies only to that profile. Per-profile overrides take precedence over
 * admin-wide ones when both exist for the same feature.
 * </p>
 *
 * <p>Uniqueness is enforced in V67 by two partial indexes
 * ({@code uq_admin_feature_global} and {@code uq_admin_feature_per_profile})
 * so at most one admin-wide row, plus at most one row per profile, exists
 * for any (admin, feature) combination.</p>
 */
@Entity
@Table(name = "admin_feature_permissions")
@Getter
@Setter
@NoArgsConstructor
public class AdminFeaturePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_account_id", nullable = false)
    private Account adminAccount;

    /**
     * Optional — when non-null, scopes the override to a single provisioning
     * profile. Null means the override applies admin-wide.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id")
    private ProvisioningProfile profile;

    /**
     * Stored as dot-notation string by {@link com.ldapportal.entity.converter.FeatureKeyConverter},
     * e.g. {@code "user.create"}.
     */
    @Column(name = "feature_key", nullable = false, length = 100)
    private FeatureKey featureKey;

    /** {@code true} = feature enabled; {@code false} = explicitly disabled. */
    @Column(nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
