// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.entity.enums.SyncScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The unit of selection + projection, 1..n per {@link SyncLink}. A sync set
 * declares which source entries are members ({@code applicability}), how they
 * are placed and transformed on the target, and the identity key that
 * correlates a source entry to its {@link Membership} row.
 *
 * <p>Phase 1 fills the projection/selection config the engine consumes
 * (placement, applicability, transform, reference attributes, delete policy).
 * The rich management surface (DTO/validation/controller/UI, brownfield) lands
 * in Phase 2 on top of these columns.
 */
@Entity
@Table(name = "sync_set")
@Getter
@Setter
@NoArgsConstructor
public class SyncSet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "link_id", nullable = false)
    private UUID linkId;

    @Column(name = "name", nullable = false)
    private String name;

    /** Base DN bounding source enumeration (a performance hint, not the selector). */
    @Column(name = "object_scope_base_dn")
    private String objectScopeBaseDn;

    @Enumerated(EnumType.STRING)
    @Column(name = "object_scope")
    private SyncScope objectScope;

    /**
     * Attribute(s) forming the correlation key. Defaults to the source's stable
     * server id (entryUUID / objectGUID / Entra id) once the engine lands;
     * null here means "not yet configured".
     */
    @Column(name = "identity_key")
    private String identityKey;

    /**
     * Target base DN the source base ({@link #objectScopeBaseDn}) is rewritten
     * to during placement. Null => identity placement (target DN == source DN).
     */
    @Column(name = "target_base_dn")
    private String targetBaseDn;

    /**
     * Membership predicate evaluated in-engine against the read entry — an
     * RFC 4515 filter (Phase 1). Null => every entry in scope is a member.
     */
    @Column(name = "applicability_filter")
    private String applicabilityFilter;

    /**
     * Comma-separated DN-valued attributes used for reference remapping and
     * closure. Null => the engine's built-in default set.
     */
    @Column(name = "reference_attributes")
    private String referenceAttributes;

    /**
     * Attribute the normalized source identity is stamped onto every target
     * entry as (the {@code sourceAnchor}). Null => not written.
     */
    @Column(name = "source_anchor_attribute")
    private String sourceAnchorAttribute;

    @Enumerated(EnumType.STRING)
    @Column(name = "delete_policy", nullable = false)
    private SyncDeletePolicy deletePolicy = SyncDeletePolicy.DELETE;

    /**
     * How often anti-entropy reconcile runs for this set, in seconds. Null => the
     * global default cadence. {@link #reconcileLastRunAt} stamps each run so the
     * scheduler can compute "due".
     */
    @Column(name = "reconcile_cadence_seconds")
    private Long reconcileCadenceSeconds;

    @Column(name = "reconcile_last_run_at")
    private OffsetDateTime reconcileLastRunAt;

    /**
     * Optional attribute rename / value-template rules. Null/empty => identity
     * transform (copy user attributes through unchanged).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transform_rules", columnDefinition = "jsonb")
    private List<SyncTransformRule> transformRules;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
