// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
 * {@link #secuserRdnAttribute}, {@link #secuserRdnValueSource},
 * {@link #groupMemberTarget}, {@link #onDemographicDelete}) are
 * nullable. ({@link #secuserObjectClasses} applies to both modes.)
 * The DB-level
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

    /**
     * Optimistic-lock counter (§4.4) — surfaced as the resource ETag and
     * checked against {@code If-Match} so a concurrent IaC apply racing a UI
     * edit fails rather than silently clobbers this directory's IVIA policy.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false)
    private boolean enabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "topology_mode", nullable = false, length = 16)
    private IsvaTopologyMode topologyMode = IsvaTopologyMode.INLINE;

    @Column(name = "sec_authority", length = 255)
    private String secAuthority = "Default";

    /** Value written to {@code secLoginType} on every secUser entry.
     * IBM's stock {@code secUser} objectClass lists this as a MUST
     * attribute (alongside {@code secAuthority}), so provisioning fails
     * with an object-class violation when it's absent. Deployment-varying;
     * defaults to {@code Default}, matching a vanilla ISVA install. */
    @Column(name = "sec_login_type", length = 255)
    private String secLoginType = "Default";

    /** secValidUntil default = now + N years. Sufficiently far-future
     * so the account doesn't "expire" by accident; admins can override
     * per-user via the profile editor. */
    @Column(name = "default_valid_until_years", nullable = false)
    private int defaultValidUntilYears = 100;

    @Enumerated(EnumType.STRING)
    @Column(name = "delete_policy", nullable = false, length = 16)
    private IsvaDeletePolicy deletePolicy = IsvaDeletePolicy.DISABLE;

    /** Gate group-membership writes on the target group carrying
     * {@code objectClass: secGroup} (refuse otherwise — ISVA ignores
     * memberships in non-secGroup groups). Opt-in: V504 reset all rows
     * to FALSE when enforcement first shipped, because the flag had
     * been persisted-but-unread since V500 and defaulting the gate ON
     * would have broken every deployment's plain-group memberships. */
    @Column(name = "require_sec_group", nullable = false)
    private boolean requireSecGroup = false;

    /** ObjectClass set defining the secUser identity. Applies to both
     * modes — inline overlays these onto the demographic entry, linked
     * stamps them on the standalone secUser entry. {@code secUser} is
     * always present (normalized in on write); extras (e.g.
     * {@code eUser}) bring in additional naming attributes. */
    @Convert(converter = SecObjectClassListConverter.class)
    @Column(name = "secuser_object_classes", columnDefinition = "TEXT")
    private List<String> secuserObjectClasses = new ArrayList<>(List.of("secUser"));

    // ── LINKED-mode-only ─────────────────────────────────────────────

    /** Base DN of the ISVA management DIT (e.g.
     * {@code secAuthority=Default,o=ibm,c=us}). Required when
     * topologyMode = LINKED; NULL when INLINE. */
    @Column(name = "management_dit_base_dn", columnDefinition = "TEXT")
    private String managementDitBaseDn;

    /** RDN attribute used for secUser entries — free-form. Stock
     * deployments use {@code secUUID} (the default) or {@code secLogin};
     * non-stock ones can name on any attribute their schema permits
     * (e.g. {@code principalName} from the {@code eUser} class). The
     * value comes from {@link #secuserRdnValueSource}. */
    @Column(name = "secuser_rdn_attribute", length = 64)
    private String secuserRdnAttribute = "secUUID";

    /** Where the RDN value comes from, decoupled from the attribute
     * name above. {@code GENERATED_UUID} (default) or {@code UID}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "secuser_rdn_value_source", length = 16)
    private IsvaRdnValueSource secuserRdnValueSource = IsvaRdnValueSource.GENERATED_UUID;

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
