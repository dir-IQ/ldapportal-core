// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Assigns approval authority to an admin account for a specific profile.
 * Used when approver mode is DATABASE.
 */
@Entity
@Table(
    name = "profile_approvers",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_profile_approver",
        columnNames = {"profile_id", "admin_account_id"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class ProfileApprover {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private ProvisioningProfile profile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "admin_account_id", nullable = false)
    private Account adminAccount;
}
