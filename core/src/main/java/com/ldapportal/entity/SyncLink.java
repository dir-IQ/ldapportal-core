// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.SyncCaptureMode;
import com.ldapportal.entity.enums.SyncChangelogHealth;
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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One directional source→target directory sync. Reshaped from the legacy
 * {@code replication_links}: a link now only declares its endpoints, capture
 * mode, and enabled state — selection and projection move onto its
 * {@link SyncSet}s. Per-link changelog cursor/health detail is reintroduced as
 * the changelog adapter lands in a later phase.
 *
 * <p>Phase-0 mapping only: this entity defines the table contract so the
 * application boots under Hibernate {@code validate}; the engine that drives
 * memberships from links arrives in subsequent phases.
 */
@Entity
@Table(name = "sync_links")
@Getter
@Setter
@NoArgsConstructor
public class SyncLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "source_dir_id", nullable = false)
    private UUID sourceDirId;

    @Column(name = "target_dir_id", nullable = false)
    private UUID targetDirId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mode", nullable = false)
    private SyncCaptureMode captureMode = SyncCaptureMode.APP_INTERCEPT;

    // ── Changelog capture (capture_mode = CHANGELOG) ────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "changelog_format")
    private ChangelogFormat changelogFormat;

    @Column(name = "changelog_base_dn")
    private String changelogBaseDn;

    /** Cursor: highest changeNumber already turned into recomputes for this link. */
    @Column(name = "changelog_last_change_number")
    private Long changelogLastChangeNumber;

    /** Last observed source head (lastChangeNumber); lag = this − cursor. */
    @Column(name = "changelog_source_last_change_number")
    private Long changelogSourceLastChangeNumber;

    @Column(name = "changelog_last_polled_at")
    private OffsetDateTime changelogLastPolledAt;

    @Column(name = "changelog_last_error")
    private String changelogLastError;

    @Column(name = "changelog_last_error_at")
    private OffsetDateTime changelogLastErrorAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "changelog_health", nullable = false)
    private SyncChangelogHealth changelogHealth = SyncChangelogHealth.HEALTHY;

    /** DB-backed single-flight lease for HA poll coordination. */
    @Column(name = "changelog_poll_claimed_at")
    private OffsetDateTime changelogPollClaimedAt;

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
