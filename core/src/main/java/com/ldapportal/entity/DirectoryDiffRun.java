// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity;

import com.ldapportal.entity.enums.DirectoryDiffPairing;
import com.ldapportal.entity.enums.DirectoryDiffPhase;
import com.ldapportal.entity.enums.DirectoryDiffStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One comparison of two directories (or two branches of one): what was
 * compared, how far it has got, and how many entries landed in each group.
 * The entries themselves are {@link DirectoryDiffEntry} rows.
 */
@Entity
@Table(name = "directory_diff_run")
@Getter
@Setter
@NoArgsConstructor
public class DirectoryDiffRun {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "requested_by", columnDefinition = "TEXT")
    private String requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DirectoryDiffStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "phase", nullable = false)
    private DirectoryDiffPhase phase;

    /** Entries read (reading phases) or accounted for (comparing) so far. */
    @Column(name = "processed", nullable = false)
    private int processed;

    /** Entries to account for, once both sides are read ({@code 0} before). */
    @Column(name = "total", nullable = false)
    private int total;

    @Column(name = "current_dn", columnDefinition = "TEXT")
    private String currentDn;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /**
     * Set by a cancel request (a targeted update), read by the run as it goes.
     * Read-only to JPA so the run's own progress saves can't clear it.
     */
    @ColumnDefault("false")
    @Column(name = "cancel_requested", nullable = false, insertable = false, updatable = false)
    private boolean cancelRequested;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "heartbeat_at", nullable = false)
    private OffsetDateTime heartbeatAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    /** The directories compared; no foreign key, so a run outlives a deleted directory (its name is kept). */
    @Column(name = "directory_a_id", nullable = false)
    private UUID directoryAId;

    @Column(name = "directory_b_id", nullable = false)
    private UUID directoryBId;

    @Column(name = "directory_a_name", columnDefinition = "TEXT")
    private String directoryAName;

    @Column(name = "directory_b_name", columnDefinition = "TEXT")
    private String directoryBName;

    @Column(name = "base_a", nullable = false, columnDefinition = "TEXT")
    private String baseA;

    @Column(name = "base_b", nullable = false, columnDefinition = "TEXT")
    private String baseB;

    @Enumerated(EnumType.STRING)
    @Column(name = "pairing", nullable = false)
    private DirectoryDiffPairing pairing;

    /** The whole request (both sides' scope and filter, the attribute choices), as JSON. */
    @Column(name = "settings", nullable = false, columnDefinition = "TEXT")
    private String settings;

    @Column(name = "same_count", nullable = false)
    private int sameCount;

    @Column(name = "different_count", nullable = false)
    private int differentCount;

    @Column(name = "only_a_count", nullable = false)
    private int onlyACount;

    @Column(name = "only_b_count", nullable = false)
    private int onlyBCount;

    /** For the Different group: attribute → how many entries it differs in, as JSON. */
    @Column(name = "attribute_counts", columnDefinition = "TEXT")
    private String attributeCounts;
}
