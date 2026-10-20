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
