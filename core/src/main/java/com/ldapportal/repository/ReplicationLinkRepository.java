// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ReplicationLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReplicationLinkRepository extends JpaRepository<ReplicationLink, UUID> {

    /**
     * First link, if any, with exactly this (source, target) directory
     * pair — <b>regardless of {@code enabled} state</b>. Backs the
     * bidirectional-rejection guard: a new A→B link is refused when a
     * B→A link already exists. Matching on enabled-only would let an
     * operator pause B→A, create A→B, then re-enable B→A and have a
     * hidden replication loop, so this intentionally ignores enabled.
     */
    Optional<ReplicationLink> findFirstBySourceDirectoryIdAndTargetDirectoryId(
            UUID sourceDirectoryId, UUID targetDirectoryId);

    /**
     * All enabled links whose source is the given directory. Called
     * exclusively from {@code ReplicationReadOps.snapshotsForSource},
     * which immediately maps each entity into a
     * {@code ReplicationLinkSnapshot} while still inside the read tx.
     *
     * <p>The {@code LEFT JOIN FETCH}es and {@code JOIN FETCH}es here
     * populate every association the snapshot factory reads —
     * {@code attributeMappings} (collection), {@code sourceDirectory},
     * {@code targetDirectory} — so the snapshot is fully self-contained
     * and the consuming non-transactional code (enqueuer hot path)
     * cannot trip {@code LazyInitializationException} regardless of
     * how it uses the snapshot. {@code DISTINCT} deduplicates the
     * Cartesian-product rows produced by the collection fetch.
     */
    @Query("""
        SELECT DISTINCT l FROM ReplicationLink l
          LEFT JOIN FETCH l.attributeMappings
               JOIN FETCH l.sourceDirectory
               JOIN FETCH l.targetDirectory
         WHERE l.sourceDirectory.id = :sourceDirectoryId
           AND l.enabled = true
        """)
    List<ReplicationLink> findAllBySourceDirectoryIdAndEnabledTrue(
            @Param("sourceDirectoryId") UUID sourceDirectoryId);

    /**
     * Fully-hydrated link for reconciliation, materialised into a
     * {@code ReplicationLinkSnapshot} inside the read tx. Fetch-joins the
     * same associations as {@link #findAllBySourceDirectoryIdAndEnabledTrue}
     * so the snapshot factory triggers no lazy load.
     */
    @Query("""
        SELECT DISTINCT l FROM ReplicationLink l
          LEFT JOIN FETCH l.attributeMappings
               JOIN FETCH l.sourceDirectory
               JOIN FETCH l.targetDirectory
         WHERE l.id = :id
        """)
    Optional<ReplicationLink> findByIdForSnapshot(@Param("id") UUID id);

    /**
     * IDs of enabled links whose reconciliation is due (next-run reached).
     * Backs the scheduler sweep; uses the partial
     * {@code idx_replication_links_reconcile_due} index.
     */
    @Query("""
        SELECT l.id FROM ReplicationLink l
         WHERE l.reconcileEnabled = true
           AND l.reconcileNextRunAt IS NOT NULL
           AND l.reconcileNextRunAt <= :now
        """)
    List<UUID> findReconcileDueIds(@Param("now") OffsetDateTime now);

    // ── Changelog capture poller (C3) ─────────────────────────────────────────

    /** IDs of enabled, CHANGELOG-capture links. Backs the poller sweep. */
    @Query("""
        SELECT l.id FROM ReplicationLink l
         WHERE l.enabled = true
           AND l.captureMode = com.ldapportal.entity.enums.ReplicationCaptureMode.CHANGELOG
        """)
    List<UUID> findChangelogCaptureLinkIds();

    /**
     * DB-backed single-flight lease (HA): stamp {@code changelog_poll_claimed_at}
     * iff the link is an enabled CHANGELOG link and the lease is free or stale.
     * Returns 1 to the winner, 0 to losers — mirrors {@code ReconciliationTxOps}.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogPollClaimedAt = :now
         WHERE l.id = :id
           AND l.enabled = true
           AND l.captureMode = com.ldapportal.entity.enums.ReplicationCaptureMode.CHANGELOG
           AND (l.changelogPollClaimedAt IS NULL OR l.changelogPollClaimedAt < :staleCutoff)
        """)
    int claimChangelogPoll(@Param("id") UUID id,
                           @Param("now") OffsetDateTime now,
                           @Param("staleCutoff") OffsetDateTime staleCutoff);

    /** Release a held poll lease (in a finally after the poll completes). */
    @Modifying
    @Query("UPDATE ReplicationLink l SET l.changelogPollClaimedAt = null WHERE l.id = :id")
    void releaseChangelogPoll(@Param("id") UUID id);

    /** Reclaim leases orphaned by a crash; returns the number released. */
    @Modifying
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogPollClaimedAt = null
         WHERE l.changelogPollClaimedAt IS NOT NULL
           AND l.changelogPollClaimedAt < :threshold
        """)
    int resetStaleChangelogPolls(@Param("threshold") OffsetDateTime threshold);

    /**
     * First-run seed: set the cursor to the current source head without
     * emitting events, only while it is still null (idempotent under replay).
     */
    @Modifying
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogLastChangeNumber = :head,
               l.changelogSourceLastChangeNumber = :head,
               l.changelogLastPolledAt = :now,
               l.changelogHealth = com.ldapportal.entity.enums.ChangelogHealth.HEALTHY,
               l.changelogLastError = null,
               l.changelogLastErrorAt = null
         WHERE l.id = :id AND l.changelogLastChangeNumber IS NULL
        """)
    int seedChangelogCursor(@Param("id") UUID id,
                            @Param("head") long head,
                            @Param("now") OffsetDateTime now);

    /**
     * Compare-and-set cursor advance: only advances when the stored cursor
     * still equals {@code expected}, so a stale poller (or a concurrent
     * operator edit) can't clobber a newer cursor. Returns 1 on success.
     */
    @Modifying
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogLastChangeNumber = :newCursor,
               l.changelogSourceLastChangeNumber = :head,
               l.changelogLastPolledAt = :now,
               l.changelogHealth = :health,
               l.changelogLastError = null,
               l.changelogLastErrorAt = null
         WHERE l.id = :id AND l.changelogLastChangeNumber = :expected
        """)
    int advanceChangelogCursor(@Param("id") UUID id,
                               @Param("expected") long expected,
                               @Param("newCursor") long newCursor,
                               @Param("head") long head,
                               @Param("health") com.ldapportal.entity.enums.ChangelogHealth health,
                               @Param("now") OffsetDateTime now);

    /** No new entries this poll: refresh the observed head, health + poll timestamp. */
    @Modifying
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogSourceLastChangeNumber = :head,
               l.changelogLastPolledAt = :now,
               l.changelogHealth = :health
         WHERE l.id = :id
        """)
    void recordChangelogPollObservation(@Param("id") UUID id,
                                        @Param("head") long head,
                                        @Param("health") com.ldapportal.entity.enums.ChangelogHealth health,
                                        @Param("now") OffsetDateTime now);

    /**
     * Gap recovery (§7A.1): entries were trimmed before we read them. CAS
     * fast-forward the cursor past the trimmed span (to {@code firstChangeNumber
     * − 1}) and flag {@code GAP_DETECTED}; reconciliation repairs the skip.
     */
    @Modifying
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogLastChangeNumber = :fastForward,
               l.changelogSourceLastChangeNumber = :head,
               l.changelogLastPolledAt = :now,
               l.changelogHealth = com.ldapportal.entity.enums.ChangelogHealth.GAP_DETECTED
         WHERE l.id = :id AND l.changelogLastChangeNumber = :expected
        """)
    int markChangelogGap(@Param("id") UUID id,
                         @Param("expected") long expected,
                         @Param("fastForward") long fastForward,
                         @Param("head") long head,
                         @Param("now") OffsetDateTime now);

    /**
     * Cursor-reset detection (§7A.2): the source head is below our cursor — the
     * changelog was reinitialized. Flag {@code CURSOR_RESET} and <b>do not</b>
     * advance; an operator reseed (or capture-mode toggle) is required.
     */
    @Modifying
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogSourceLastChangeNumber = :head,
               l.changelogLastPolledAt = :now,
               l.changelogHealth = com.ldapportal.entity.enums.ChangelogHealth.CURSOR_RESET
         WHERE l.id = :id
        """)
    void markChangelogCursorReset(@Param("id") UUID id,
                                  @Param("head") long head,
                                  @Param("now") OffsetDateTime now);

    /** Record a poll/parse/connection error string for operator diagnosis. */
    @Modifying
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogLastError = :error,
               l.changelogLastErrorAt = :now,
               l.changelogLastPolledAt = :now
         WHERE l.id = :id
        """)
    void recordChangelogPollError(@Param("id") UUID id,
                                  @Param("error") String error,
                                  @Param("now") OffsetDateTime now);

    // ── Operator remediation (§7A.12) ─────────────────────────────────────────

    /**
     * Reseed: drop the cursor so the next poll re-seeds from the current source
     * head (no history replay), and clear health/error/lease. Recovers a
     * CURSOR_RESET link without a capture-mode toggle.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogLastChangeNumber = null,
               l.changelogSourceLastChangeNumber = null,
               l.changelogLastPolledAt = null,
               l.changelogPollClaimedAt = null,
               l.changelogHealth = com.ldapportal.entity.enums.ChangelogHealth.HEALTHY,
               l.changelogLastError = null,
               l.changelogLastErrorAt = null
         WHERE l.id = :id
        """)
    int reseedChangelogCursor(@Param("id") UUID id);

    /** Rewind: set the cursor to an operator-supplied changeNumber and reset health. */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogLastChangeNumber = :target,
               l.changelogHealth = com.ldapportal.entity.enums.ChangelogHealth.HEALTHY,
               l.changelogLastError = null,
               l.changelogLastErrorAt = null
         WHERE l.id = :id
        """)
    int rewindChangelogCursor(@Param("id") UUID id, @Param("target") long target);

    /** Re-enable: clear a degraded health/error + lease and retry from the current cursor. */
    @Modifying(clearAutomatically = true)
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogHealth = com.ldapportal.entity.enums.ChangelogHealth.HEALTHY,
               l.changelogLastError = null,
               l.changelogLastErrorAt = null,
               l.changelogPollClaimedAt = null
         WHERE l.id = :id
        """)
    int clearChangelogHealthError(@Param("id") UUID id);

    /**
     * STALLED detection (§7A.7): flag enabled CHANGELOG links not polled since
     * {@code threshold}. Only flips a HEALTHY/LAGGING link (never overrides
     * GAP_DETECTED / CURSOR_RESET / DISABLED_CONFIG_ERROR); a never-polled link
     * (null {@code changelogLastPolledAt}) is awaiting its first poll, not stalled.
     * Clears automatically on the next successful poll.
     */
    @Modifying
    @Query("""
        UPDATE ReplicationLink l
           SET l.changelogHealth = com.ldapportal.entity.enums.ChangelogHealth.STALLED
         WHERE l.enabled = true
           AND l.captureMode = com.ldapportal.entity.enums.ReplicationCaptureMode.CHANGELOG
           AND l.changelogLastPolledAt IS NOT NULL
           AND l.changelogLastPolledAt < :threshold
           AND l.changelogHealth IN (com.ldapportal.entity.enums.ChangelogHealth.HEALTHY,
                                     com.ldapportal.entity.enums.ChangelogHealth.LAGGING)
        """)
    int markStalledChangelogLinks(@Param("threshold") OffsetDateTime threshold);
}
