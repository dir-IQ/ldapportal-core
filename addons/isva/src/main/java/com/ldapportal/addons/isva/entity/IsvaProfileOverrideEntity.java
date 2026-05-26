// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-profile ISVA override row. Primary key is the
 * {@code provisioning_profiles.id} so the relationship is 1:1 (or
 * 0:1 — a profile with no row is treated as
 * {@link IsvaProfileOverride#INHERIT}).
 *
 * <p>Addon-owned and keyed by profile id only; core stays
 * ISVA-agnostic. The FK + {@code CHECK} constraint live in the
 * Flyway migration ({@code V501}).</p>
 */
@Entity
@Table(name = "isva_profile_override")
@Getter
@Setter
@NoArgsConstructor
public class IsvaProfileOverrideEntity {

    @Id
    @Column(name = "profile_id", nullable = false, updatable = false)
    private UUID profileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "override", nullable = false, length = 16)
    private IsvaProfileOverride override = IsvaProfileOverride.INHERIT;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;
}
