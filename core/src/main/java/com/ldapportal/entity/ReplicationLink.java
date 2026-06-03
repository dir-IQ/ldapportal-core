// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.ChangelogHealth;
import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReplicationCaptureMode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
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
 * Source → target replication configuration. One row per directional
 * pair. Attribute-mapping rules attach via the
 * {@link ReplicationLinkAttrMapping} child rows.
 *
 * <p>See {@code docs/plans/2026-05-30-directory-sync-design.md}.
 */
@Entity
@Table(name = "replication_links")
@Getter
@Setter
@NoArgsConstructor
public class ReplicationLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_dir_id", nullable = false)
    private DirectoryConnection sourceDirectory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_dir_id", nullable = false)
    private DirectoryConnection targetDirectory;

    /**
     * Source-side base DN for DN rewriting. NULL pair (with
     * {@link #targetBaseDn}) means identity mapping — source and target
     * use the same DN. Both must be NULL or both set, enforced by a
     * DB CHECK constraint.
     */
    @Column(name = "source_base_dn", length = 500)
    private String sourceBaseDn;

    @Column(name = "target_base_dn", length = 500)
    private String targetBaseDn;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "auto_create_on_missing", nullable = false)
    private boolean autoCreateOnMissing = false;

    @OneToMany(mappedBy = "link", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    private List<ReplicationLinkAttrMapping> attributeMappings = new ArrayList<>();

    // ── Periodic reconciliation config (R-P0) ────────────────────────────────
    // Opt-in per link; off by default. See
    // docs/plans/2026-05-31-replication-reconciliation-design.md §5.1.

    @Column(name = "reconcile_enabled", nullable = false)
    private boolean reconcileEnabled = false;

    /** Resolution mode for missing/drift findings. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reconcile_mode", nullable = false, length = 20)
    private ReconcileMode reconcileMode = ReconcileMode.REVIEW;

    /** Operator-chosen start of the first run; drives the initial next-run. */
    @Column(name = "reconcile_first_run_at")
    private OffsetDateTime reconcileFirstRunAt;

    /** Repeat cadence in seconds. Floor of 3600 (1 hour) when enabled. */
    @Column(name = "reconcile_interval_secs")
    private Integer reconcileIntervalSecs;

    /** Next due time the scheduler polls on; advanced by whole intervals. */
    @Column(name = "reconcile_next_run_at")
    private OffsetDateTime reconcileNextRunAt;

    @Column(name = "reconcile_last_run_at")
    private OffsetDateTime reconcileLastRunAt;

    /** How EXTRA_IN_TARGET entries are resolved; independent of the mode. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reconcile_delete_action", nullable = false, length = 20)
    private ReconcileDeleteAction reconcileDeleteAction = ReconcileDeleteAction.REVIEW;

    // ── Changelog capture config (C1) ────────────────────────────────────────
    // Opt-in per link; APP_INTERCEPT by default so existing links are
    // unaffected. See docs/plans/2026-06-03-changelog-replication-design.md §2.

    /** How source changes are detected; exclusive per link. */
    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mode", nullable = false, length = 20)
    private ReplicationCaptureMode captureMode = ReplicationCaptureMode.APP_INTERCEPT;

    /** Changelog format the source exposes; v1 accepts only {@code DSEE_CHANGELOG}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "changelog_format", length = 25)
    private ChangelogFormat changelogFormat;

    /** Changelog base DN; the service defaults this to {@code cn=changelog} when blank. */
    @Column(name = "changelog_base_dn", length = 500)
    private String changelogBaseDn;

    /** Cursor / high-water mark: highest {@code changeNumber} already enqueued. */
    @Column(name = "changelog_last_change_number")
    private Long changelogLastChangeNumber;

    /**
     * Optional RFC 4515 filter. An entry within the replicated DIT that
     * MATCHES this filter is excluded from replication entirely (never
     * created, modified, or deleted on the target). Null = replicate the
     * whole DIT. Applies to BOTH capture modes and reconciliation (§7B).
     */
    @Column(name = "exclude_filter", length = 2000)
    private String excludeFilter;

    // ── Changelog liveness / health surfacing (§7A) ──────────────────────────

    /** Last observed source head ({@code lastChangeNumber}); lag = this − cursor. */
    @Column(name = "changelog_source_last_change_number")
    private Long changelogSourceLastChangeNumber;

    @Column(name = "changelog_last_polled_at")
    private OffsetDateTime changelogLastPolledAt;

    @Column(name = "changelog_last_error", columnDefinition = "TEXT")
    private String changelogLastError;

    @Column(name = "changelog_last_error_at")
    private OffsetDateTime changelogLastErrorAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "changelog_health", nullable = false, length = 24)
    private ChangelogHealth changelogHealth = ChangelogHealth.HEALTHY;

    /** DB-backed single-flight poll lease for HA (mirrors {@code ReconciliationTxOps}). */
    @Column(name = "changelog_poll_claimed_at")
    private OffsetDateTime changelogPollClaimedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
