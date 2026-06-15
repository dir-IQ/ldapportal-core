// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Declares a group that users are automatically added to when
 * provisioned via this profile.
 */
@Entity
@Table(
    name = "profile_group_assignments",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_profile_group",
        columnNames = {"profile_id", "group_dn"}
    )
)
@Getter
@Setter
@NoArgsConstructor
public class ProfileGroupAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private ProvisioningProfile profile;

    @Column(name = "group_dn", nullable = false, length = 500)
    private String groupDn;

    @Column(name = "member_attribute", nullable = false, length = 50)
    private String memberAttribute = "member";

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;
}
