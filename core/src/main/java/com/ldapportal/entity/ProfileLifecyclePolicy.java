// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.ExpiryAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Account lifecycle rules attached to a provisioning profile.
 * At most one policy per profile (enforced by unique constraint).
 */
@Entity
@Table(name = "profile_lifecycle_policies")
@Getter
@Setter
@NoArgsConstructor
public class ProfileLifecyclePolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false, unique = true)
    private ProvisioningProfile profile;

    @Column(name = "expires_after_days")
    private Integer expiresAfterDays;

    @Column(name = "max_renewals")
    private Integer maxRenewals;

    @Column(name = "renewal_days")
    private Integer renewalDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "on_expiry_action", nullable = false, length = 20)
    private ExpiryAction onExpiryAction = ExpiryAction.DISABLE;

    @Column(name = "on_expiry_move_dn", length = 500)
    private String onExpiryMoveDn;

    @Column(name = "on_expiry_remove_groups", nullable = false)
    private boolean onExpiryRemoveGroups = true;

    @Column(name = "on_expiry_notify", nullable = false)
    private boolean onExpiryNotify = true;

    @Column(name = "warning_days_before")
    private Integer warningDaysBefore;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
