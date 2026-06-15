// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.repository;

import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.OutboxStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxEntryRepository extends JpaRepository<OutboxEntry, UUID> {

    /**
     * Claim a batch of PENDING rows ready to dispatch. Atomically transitions
     * them to DELIVERING, increments attempts, and stamps claimed_at so the
     * stale-reset sweep can age the claim. Uses FOR UPDATE SKIP LOCKED so
     * concurrent dispatchers (in the future HA world) don't contend.
     * Returns the claimed row IDs.
     */
    @Modifying
    @Query(value = """
        UPDATE event_outbox
           SET status     = 'DELIVERING',
               attempts   = attempts + 1,
               claimed_at = :now
         WHERE id IN (
             SELECT id FROM event_outbox
              WHERE status = :pendingStatus
                AND next_attempt_at <= :now
              ORDER BY next_attempt_at
              FOR UPDATE SKIP LOCKED
              LIMIT :batchSize
         )
         RETURNING id
        """, nativeQuery = true)
    List<UUID> claimBatch(@Param("pendingStatus") String pendingStatus,
                          @Param("now") Instant now,
                          @Param("batchSize") int batchSize);

    /**
     * Crash recovery: DELIVERING rows stuck longer than MAX_DELIVERING get
     * flipped back to PENDING so they retry on the next tick.
     *
     * <p>Predicate is {@code claimed_at < cutoff}, NOT
     * {@code next_attempt_at < cutoff}: the latter would false-reset any
     * backlog-drain claim the instant it lands (next_attempt_at can be
     * far in the past at claim time when the dispatcher was down or the
     * backoff already elapsed). The mistaken reset opens a double-
     * delivery window since the dispatcher's in-flight HTTP POST runs
     * outside any DB transaction.
     */
    @Modifying
    @Query(value = """
        UPDATE event_outbox
           SET status          = 'PENDING',
               next_attempt_at = :resetTo,
               claimed_at      = NULL
         WHERE status = 'DELIVERING'
           AND claimed_at IS NOT NULL
           AND claimed_at < :cutoff
        """, nativeQuery = true)
    int resetStaleDelivering(@Param("cutoff") Instant cutoff,
                             @Param("resetTo") Instant resetTo);

    Page<OutboxEntry> findAllByStatus(OutboxStatus status, Pageable pageable);

    Page<OutboxEntry> findAllBySubscriptionId(UUID subscriptionId, Pageable pageable);

    Page<OutboxEntry> findAllBySubscriptionIdAndStatus(
            UUID subscriptionId, OutboxStatus status, Pageable pageable);

    Page<OutboxEntry> findAllByEventType(String eventType, Pageable pageable);

    Page<OutboxEntry> findAllByCreatedAtAfter(Instant since, Pageable pageable);

    long countByStatus(OutboxStatus status);
}
