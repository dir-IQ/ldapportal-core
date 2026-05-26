// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.ApproverMode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Approval workflow configuration for a provisioning profile.
 * At most one config per profile (enforced by unique constraint).
 */
@Entity
@Table(name = "profile_approval_configs")
@Getter
@Setter
@NoArgsConstructor
public class ProfileApprovalConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false, unique = true)
    private ProvisioningProfile profile;

    @Column(name = "require_approval", nullable = false)
    private boolean requireApproval = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_mode", nullable = false, length = 20)
    private ApproverMode approverMode = ApproverMode.DATABASE;

    @Column(name = "approver_group_dn", length = 500)
    private String approverGroupDn;

    @Column(name = "auto_escalate_days")
    private Integer autoEscalateDays;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escalation_account_id")
    private Account escalationAccount;
}
