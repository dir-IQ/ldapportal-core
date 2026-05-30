// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReplicationEventRepository extends JpaRepository<ReplicationEvent, UUID> {

    /**
     * Distinct link IDs that have at least one event ready to deliver
     * (PENDING, or FAILED with retry-time reached). The worker iterates
     * these and picks the earliest event per link, enforcing per-link
     * FIFO without scanning every row.
     */
    @Query("""
        SELECT DISTINCT e.link.id
        FROM ReplicationEvent e
        WHERE e.status IN ('PENDING', 'FAILED')
          AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now)
        """)
    List<UUID> findLinkIdsWithClaimableEvents(@Param("now") OffsetDateTime now);

    /**
     * The earliest claimable event for a given link — the FIFO head.
     * The worker reads this, attempts to atomically claim it via
     * {@link #tryClaim}, then delivers. Returning an Optional rather
     * than throwing keeps the iteration loop short on transient
     * race-windows (another thread claimed it first).
     */
    @Query("""
        SELECT e FROM ReplicationEvent e
        WHERE e.link.id = :linkId
          AND e.status IN ('PENDING', 'FAILED')
          AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now)
        ORDER BY e.enqueuedAt ASC
        LIMIT 1
        """)
    java.util.Optional<ReplicationEvent> findEarliestClaimableForLink(
            @Param("linkId") UUID linkId,
            @Param("now") OffsetDateTime now);

    /**
     * Atomic claim: transition an event from PENDING / FAILED to
     * IN_FLIGHT. Returns 1 if the caller won the race, 0 if another
     * worker beat them to it. The version-style guard is the WHERE
     * clause on current status — equivalent to compare-and-swap.
     */
    @Modifying
    @Query("""
        UPDATE ReplicationEvent e
        SET e.status = com.ldapportal.entity.enums.ReplicationEventStatus.IN_FLIGHT
        WHERE e.id = :id
          AND e.status IN ('PENDING', 'FAILED')
        """)
    int tryClaim(@Param("id") UUID id);

    /**
     * Settle a successful delivery. Caller marks the row DELIVERED with
     * the delivery timestamp; no further worker activity touches it.
     */
    @Modifying
    @Query("""
        UPDATE ReplicationEvent e
        SET e.status        = com.ldapportal.entity.enums.ReplicationEventStatus.DELIVERED,
            e.deliveredAt   = :deliveredAt,
            e.nextAttemptAt = NULL,
            e.lastError     = NULL
        WHERE e.id = :id
        """)
    int markDelivered(@Param("id") UUID id, @Param("deliveredAt") OffsetDateTime deliveredAt);

    /**
     * Settle a failed delivery. The caller has computed the new
     * status (FAILED with retry pending, or DEAD_LETTERED with no
     * retry budget left) and the next attempt time per the backoff
     * policy.
     */
    @Modifying
    @Query("""
        UPDATE ReplicationEvent e
        SET e.status        = :status,
            e.attempts      = :attempts,
            e.nextAttemptAt = :nextAttemptAt,
            e.lastError     = :lastError
        WHERE e.id = :id
        """)
    int markFailure(@Param("id") UUID id,
                    @Param("status") ReplicationEventStatus status,
                    @Param("attempts") int attempts,
                    @Param("nextAttemptAt") OffsetDateTime nextAttemptAt,
                    @Param("lastError") String lastError);
}
