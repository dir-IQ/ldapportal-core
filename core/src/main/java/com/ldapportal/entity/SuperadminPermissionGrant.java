// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.SuperadminPermission;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single system-scoped {@link SuperadminPermission} granted to a SUPERADMIN
 * account. The presence of a row means the permission is granted; absence
 * means it is not (there is no per-row enable flag — unlike the directory
 * {@link AdminFeaturePermission} model, superadmin permissions have no base-role
 * defaults to override).
 *
 * <p>Uniqueness on {@code (account_id, permission)} is enforced by
 * {@code uq_superadmin_permission} (see the V13 migration).</p>
 */
@Entity
@Table(name = "superadmin_permission_grants")
@Getter
@Setter
@NoArgsConstructor
public class SuperadminPermissionGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /**
     * Stored as a dot-notation string by
     * {@link com.ldapportal.entity.converter.SuperadminPermissionConverter},
     * e.g. {@code "superadmin.manage_application_accounts"}.
     */
    @Column(name = "permission", nullable = false, length = 100)
    private SuperadminPermission permission;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
