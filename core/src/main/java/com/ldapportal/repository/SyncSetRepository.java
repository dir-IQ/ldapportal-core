// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.SyncSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link SyncSet}. Phase-0 mapping only.
 */
public interface SyncSetRepository extends JpaRepository<SyncSet, UUID> {

    List<SyncSet> findAllByLinkId(UUID linkId);

    List<SyncSet> findAllByEnabledTrue();

    /**
     * Cache a content-verify outcome on the set (drift snapshot for the dashboard).
     * A targeted update so it never collides with the optimistic-lock version the
     * config/engine paths increment — these are denormalized stats, not config.
     */
    @Transactional
    @Modifying
    @Query("update SyncSet s set s.lastVerifiedAt = :at, s.verifyMissingCount = :missing, "
            + "s.verifyOrphanCount = :orphan, s.verifyMismatchCount = :mismatch where s.id = :id")
    int recordVerifyResult(@Param("id") UUID id, @Param("at") OffsetDateTime at,
                           @Param("missing") int missing, @Param("orphan") int orphan,
                           @Param("mismatch") int mismatch);

    /**
     * Ask for a reconcile of every enabled set on a link: bumps the request
     * counter, so {@code SyncReconcileScheduler} sees the sets as due on its next
     * tick. The changelog poller uses this instead of reconciling inline, so a
     * seed or gap never blocks the poll thread and a failing reconcile can't hold
     * the cursor back. A targeted update, like {@link #recordVerifyResult}, so it
     * never collides with the config/engine optimistic-lock version.
     */
    @Transactional
    @Modifying
    @Query("update SyncSet s set s.reconcileRequestSeq = s.reconcileRequestSeq + 1 "
            + "where s.linkId = :linkId and s.enabled = true")
    int requestReconcile(@Param("linkId") UUID linkId);

    /** Ask for a reconcile of one set (see {@link #requestReconcile}); runs once the set is enabled. */
    @Transactional
    @Modifying
    @Query("update SyncSet s set s.reconcileRequestSeq = s.reconcileRequestSeq + 1 where s.id = :id")
    int requestReconcileOfSet(@Param("id") UUID id);

    /**
     * Record that a reconcile which loaded {@code requestSeq} at its start has
     * finished: always as attempted, and as handled only when its source
     * enumeration was complete. Neither mark ever moves backwards (two
     * reconciles finishing out of order), and any request raised after that
     * start stays pending.
     */
    default void markReconcileRun(UUID id, long requestSeq, boolean complete) {
        markReconcileAttempted(id, requestSeq);
        if (complete) {
            markReconcileHandled(id, requestSeq);
        }
    }

    /**
     * Claim the reconcile lease for a set: succeeds when no reconcile holds it or
     * the holder's heartbeat is older than {@code staleBefore} (its server
     * stopped). Returns 1 if claimed, 0 if another reconcile is running.
     */
    @Transactional
    @Modifying
    @Query("update SyncSet s set s.reconcileLeaseToken = :token, s.reconcileLeaseHeartbeatAt = :now "
            + "where s.id = :id and (s.reconcileLeaseToken is null or s.reconcileLeaseHeartbeatAt < :staleBefore)")
    int claimReconcile(@Param("id") UUID id, @Param("token") UUID token, @Param("now") OffsetDateTime now,
                       @Param("staleBefore") OffsetDateTime staleBefore);

    /** Keep a held lease alive. Returns 0 when the lease is no longer this token's. */
    @Transactional
    @Modifying
    @Query("update SyncSet s set s.reconcileLeaseHeartbeatAt = :now "
            + "where s.id = :id and s.reconcileLeaseToken = :token")
    int heartbeatReconcile(@Param("id") UUID id, @Param("token") UUID token, @Param("now") OffsetDateTime now);

    /** Release a held lease (a no-op when it is no longer this token's). */
    @Transactional
    @Modifying
    @Query("update SyncSet s set s.reconcileLeaseToken = null, s.reconcileLeaseHeartbeatAt = null "
            + "where s.id = :id and s.reconcileLeaseToken = :token")
    int releaseReconcile(@Param("id") UUID id, @Param("token") UUID token);

    /**
     * Stamp a finished reconcile. Also bumps {@code updatedAt}, which marks a
     * per-entry preview started before it as outdated. A targeted update, so it
     * never collides with an admin edit's optimistic-lock version.
     */
    @Transactional
    @Modifying
    @Query("update SyncSet s set s.reconcileLastRunAt = :at, s.updatedAt = :at where s.id = :id")
    int stampReconcileRun(@Param("id") UUID id, @Param("at") OffsetDateTime at);

    @Transactional
    @Modifying
    @Query("update SyncSet s set s.reconcileAttemptedSeq = :requestSeq "
            + "where s.id = :id and s.reconcileAttemptedSeq < :requestSeq")
    int markReconcileAttempted(@Param("id") UUID id, @Param("requestSeq") long requestSeq);

    @Transactional
    @Modifying
    @Query("update SyncSet s set s.reconcileHandledSeq = :requestSeq "
            + "where s.id = :id and s.reconcileHandledSeq < :requestSeq")
    int markReconcileHandled(@Param("id") UUID id, @Param("requestSeq") long requestSeq);
}
