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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Per-directory ISVA full-mode integration configuration. Primary
 * key is the {@code directory_connection_id} so the relationship
 * to {@link com.ldapportal.entity.DirectoryConnection} is 1:1 (or
 * 0:1, when no row exists for a directory the addon stays inert).
 *
 * <p>See the schema-mapping table in
 * {@code docs/superpowers/specs/2026-05-20-isva-full-mode-integration-design.md}
 * for what each field controls.</p>
 *
 * <p>Linked-mode-only fields ({@link #managementDitBaseDn},
 * {@link #secuserRdnAttribute}, {@link #groupMemberTarget},
 * {@link #onDemographicDelete}) are nullable. The DB-level
 * {@code CHECK} constraint in the Flyway migration enforces that
 * {@code management_dit_base_dn} is non-null when
 * {@code topology_mode = LINKED}.</p>
 */
@Entity
@Table(name = "vendor_integration_isva_config")
@Getter
@Setter
@NoArgsConstructor
public class VendorIntegrationIsvaConfig {

    /**
     * Foreign key + primary key into {@code directory_connections.id}.
     * No JPA @OneToOne mapping — keeping the entity self-contained
     * avoids cycles with core's DirectoryConnection. The FK
     * constraint lives in the SQL migration.
     */
    @Id
    @Column(name = "directory_connection_id", nullable = false, updatable = false)
    private UUID directoryConnectionId;

    @Column(nullable = false)
    private boolean enabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "topology_mode", nullable = false, length = 16)
    private IsvaTopologyMode topologyMode = IsvaTopologyMode.INLINE;

    @Column(name = "sec_authority", length = 255)
    private String secAuthority = "Default";

    /** secValidUntil default = now + N years. Sufficiently far-future
     * so the account doesn't "expire" by accident; admins can override
     * per-user via the profile editor. */
    @Column(name = "default_valid_until_years", nullable = false)
    private int defaultValidUntilYears = 100;

    @Enumerated(EnumType.STRING)
    @Column(name = "delete_policy", nullable = false, length = 16)
    private IsvaDeletePolicy deletePolicy = IsvaDeletePolicy.DISABLE;

    @Column(name = "require_sec_group", nullable = false)
    private boolean requireSecGroup = true;

    // ── LINKED-mode-only ─────────────────────────────────────────────

    /** Base DN of the ISVA management DIT (e.g.
     * {@code secAuthority=Default,o=ibm,c=us}). Required when
     * topologyMode = LINKED; NULL when INLINE. */
    @Column(name = "management_dit_base_dn", columnDefinition = "TEXT")
    private String managementDitBaseDn;

    /** RDN attribute used for secUser entries — usually {@code secUUID},
     * sometimes {@code secLogin} in older deployments. */
    @Column(name = "secuser_rdn_attribute", length = 64)
    private String secuserRdnAttribute = "secUUID";

    @Enumerated(EnumType.STRING)
    @Column(name = "group_member_target", length = 16)
    private IsvaGroupMemberTarget groupMemberTarget = IsvaGroupMemberTarget.DEMOGRAPHIC_DN;

    @Enumerated(EnumType.STRING)
    @Column(name = "on_demographic_delete", length = 24)
    private IsvaDemographicDeleteMode onDemographicDelete = IsvaDemographicDeleteMode.LEAVE;

    // ── Audit columns ────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;
}
