// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.BaseRole;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Profile-scoped permission assignment. Replaces {@link AdminRealmRole}.
 *
 * <p>An admin may have different base roles across different profiles.
 * Absence of a row for a given (admin, profile) pair = access denied to that profile.</p>
 */
@Entity
@Table(
    name = "admin_profile_roles",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_admin_profile_role",
        columnNames = {"admin_account_id", "profile_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class AdminProfileRole {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_account_id", nullable = false)
    private Account adminAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private ProvisioningProfile profile;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_role", nullable = false, length = 20)
    private BaseRole baseRole;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
