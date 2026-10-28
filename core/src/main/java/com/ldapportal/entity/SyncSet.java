// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.SyncHoldForReview;
import com.ldapportal.entity.enums.SyncLoggingLevel;
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
import org.hibernate.annotations.ColumnDefault;
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
 * (placement, applicability, transform, reference attributes, hold for review).
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

    /** Base DN bounding source enumeration (a performance hint, not the selector). Required. */
    @Column(name = "object_scope_base_dn", nullable = false)
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

    // Safe default: hold scope-exit deletions for operator review rather than
    // applying them. An operator opts out (NONE) or holds every write (ALL).
    @Enumerated(EnumType.STRING)
    @Column(name = "hold_for_review", nullable = false)
    private SyncHoldForReview holdForReview = SyncHoldForReview.DELETES;

    /**
     * How often anti-entropy reconcile runs for this set, in seconds. Null => the
     * global default cadence. {@link #reconcileLastRunAt} stamps each run so the
     * scheduler can compute "due".
     */
    @Column(name = "reconcile_cadence_seconds")
    private Long reconcileCadenceSeconds;

    /** Read-only to JPA: only {@code SyncSetRepository.stampReconcileRun} writes it. */
    @Column(name = "reconcile_last_run_at", insertable = false, updatable = false)
    private OffsetDateTime reconcileLastRunAt;

    /**
     * Reconcile-request counters. The changelog poller increments
     * {@code requestSeq} ({@code SyncSetRepository.requestReconcile}). A finishing
     * reconcile records the {@code requestSeq} it loaded at its start as
     * {@code attemptedSeq}, and also as {@code handledSeq} when its source
     * enumeration completed ({@code SyncSetRepository.markReconcileRun}), so a
     * request raised while it ran stays pending. {@code SyncReconcileScheduler}
     * treats {@code requestSeq > attemptedSeq} as due now and
     * {@code requestSeq > handledSeq} as due for a spaced retry. Read-only to JPA
     * (insertable/updatable = false): only those targeted updates write them, so
     * an entity save from a reconcile that loaded the set earlier can never
     * overwrite a newer request.
     */
    @ColumnDefault("0")
    @Column(name = "reconcile_request_seq", nullable = false, insertable = false, updatable = false)
    private long reconcileRequestSeq;

    @ColumnDefault("0")
    @Column(name = "reconcile_attempted_seq", nullable = false, insertable = false, updatable = false)
    private long reconcileAttemptedSeq;

    @ColumnDefault("0")
    @Column(name = "reconcile_handled_seq", nullable = false, insertable = false, updatable = false)
    private long reconcileHandledSeq;

    /**
     * Single-flight lease for this set's reconcile across app instances: the
     * running reconcile's token and its last heartbeat ({@code null} when none
     * runs). Read-only to JPA, like the request counters: only the targeted
     * claim / heartbeat / release updates in {@code SyncSetRepository} write it,
     * so an admin edit saving the set can't clear a running reconcile's lease.
     */
    @Column(name = "reconcile_lease_token", insertable = false, updatable = false)
    private UUID reconcileLeaseToken;

    @Column(name = "reconcile_lease_heartbeat_at", insertable = false, updatable = false)
    private OffsetDateTime reconcileLeaseHeartbeatAt;

    /**
     * Optional attribute rename / value-template rules. Null/empty => identity
     * transform (copy user attributes through unchanged).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "transform_rules", columnDefinition = "jsonb")
    private List<SyncTransformRule> transformRules;

    /**
     * Attribute names never copied from source nor deleted from target.
     * {@code null} => the engine defaults (operational + password values, see
     * {@link com.ldapportal.ldap.sync.SyncExcludedAttributes}); a non-null list
     * (including empty) is used verbatim — empty means "exclude nothing".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "excluded_attributes", columnDefinition = "jsonb")
    private List<String> excludedAttributes;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * How much of each run the history keeps: counts only, or also every
     * changed or flagged entry's DN (downloadable as CSV). Summary by default:
     * a detailed large run stores one row per entry.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "logging_level", nullable = false, length = 16)
    private SyncLoggingLevel loggingLevel = SyncLoggingLevel.SUMMARY;

    // ── Last content-verification snapshot (drift, cached for the dashboard) ─────
    // SyncContentVerifier re-reads both directories, so its result is too costly
    // to compute on every dashboard load; the scheduled reconcile and a manual
    // verify both refresh these. Null counts => never verified (no drift claimed).

    @Column(name = "last_verified_at")
    private OffsetDateTime lastVerifiedAt;

    @Column(name = "verify_missing_count")
    private Integer verifyMissingCount;

    @Column(name = "verify_orphan_count")
    private Integer verifyOrphanCount;

    @Column(name = "verify_mismatch_count")
    private Integer verifyMismatchCount;

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
