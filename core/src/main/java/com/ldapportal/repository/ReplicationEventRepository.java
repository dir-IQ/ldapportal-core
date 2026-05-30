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
     *
     * <p>JOIN FETCH the link and both directories so the worker can
     * read those associations after the entity is detached
     * ({@code open-in-view=false} closes the session as soon as this
     * query returns). Without the fetch, the first
     * {@code event.getLink().getTargetDirectory()} access in
     * {@code ReplicationDelivery} throws
     * {@code LazyInitializationException} and no event ever delivers.
     */
    @Query("""
        SELECT e FROM ReplicationEvent e
          JOIN FETCH e.link l
          JOIN FETCH l.sourceDirectory
          JOIN FETCH l.targetDirectory
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

    // ── UI surfacing: list / filter / counts ──────────────────────────────────

    /**
     * Per-link health rollup in a single query. Returns one row per
     * link: counts of PENDING / FAILED / DEAD_LETTERED events, plus
     * the timestamp of the most recently DELIVERED event (the "lag"
     * indicator). NULL last-delivered is acceptable (no event ever
     * succeeded yet — first delivery still pending).
     *
     * <p>Returned as Object[] rows because JPQL can't construct an
     * arbitrary record from a GROUP BY result without a constructor
     * expression listing every field at the call site. The service
     * unpacks into a Map<UUID, LinkHealth>.
     */
    @Query("""
        SELECT e.link.id,
               SUM(CASE WHEN e.status = com.ldapportal.entity.enums.ReplicationEventStatus.PENDING       THEN 1 ELSE 0 END),
               SUM(CASE WHEN e.status = com.ldapportal.entity.enums.ReplicationEventStatus.FAILED        THEN 1 ELSE 0 END),
               SUM(CASE WHEN e.status = com.ldapportal.entity.enums.ReplicationEventStatus.DEAD_LETTERED THEN 1 ELSE 0 END),
               MAX(e.deliveredAt)
        FROM ReplicationEvent e
        WHERE e.link.id IN :linkIds
        GROUP BY e.link.id
        """)
    List<Object[]> findHealthRollup(@Param("linkIds") java.util.Collection<UUID> linkIds);

    /** System-wide count of dead-lettered events — drives the dashboard metric. */
    long countByStatus(ReplicationEventStatus status);

    /**
     * Reset events stuck in IN_FLIGHT for longer than the given
     * threshold back to PENDING so the worker picks them up again.
     * Used by {@link ReplicationWorker#resetStaleInFlight()}; without
     * this, a JVM crash mid-delivery permanently wedges an event
     * (and behind it, every later event for the same link via the
     * per-link FIFO).
     */
    @Modifying
    @Query("""
        UPDATE ReplicationEvent e
        SET e.status = com.ldapportal.entity.enums.ReplicationEventStatus.PENDING,
            e.nextAttemptAt = NULL
        WHERE e.status = com.ldapportal.entity.enums.ReplicationEventStatus.IN_FLIGHT
          AND e.enqueuedAt < :threshold
        """)
    int resetStaleInFlight(@Param("threshold") OffsetDateTime threshold);

    /**
     * Number of distinct links with at least one unresolved event
     * (PENDING or FAILED) older than the given timestamp. Drives the
     * REPLICATION_LAG_HIGH awareness item — a link with old PENDING
     * events is either deeply backlogged or its target is
     * unreachable.
     */
    @Query("""
        SELECT count(DISTINCT e.link.id) FROM ReplicationEvent e
        WHERE e.status IN ('PENDING', 'FAILED')
          AND e.enqueuedAt < :threshold
        """)
    long countLinksLaggingSince(@Param("threshold") OffsetDateTime threshold);

    /**
     * Per-link event list ordered by enqueued_at desc. Filter args
     * are all optional (controller passes null when the operator
     * didn't supply a constraint).
     */
    @Query("""
        SELECT e FROM ReplicationEvent e
        WHERE e.link.id = :linkId
          AND (:status IS NULL OR e.status = :status)
        ORDER BY e.enqueuedAt DESC
        """)
    org.springframework.data.domain.Page<ReplicationEvent> findByLinkAndStatus(
            @Param("linkId") UUID linkId,
            @Param("status") ReplicationEventStatus status,
            org.springframework.data.domain.Pageable pageable);

    // ── Operator actions: retry, skip, acknowledge ────────────────────────────

    /**
     * Operator-initiated retry: flip a FAILED / DEAD_LETTERED /
     * SKIPPED / ACKNOWLEDGED / IN_FLIGHT event back to PENDING so the
     * worker picks it up on the next tick. Resets retry timing but
     * preserves attempts (so the backoff continues from where it was
     * — not "fresh start" semantics, which would let an operator
     * infinite-loop a hopeless event).
     *
     * <p>IN_FLIGHT is included because the worker's automated stale-
     * reset runs on a long interval (10 minutes); operators who need
     * to recover from a known-crashed worker faster than that should
     * be able to retry directly without DBA help.
     */
    @Modifying
    @Query("""
        UPDATE ReplicationEvent e
        SET e.status        = com.ldapportal.entity.enums.ReplicationEventStatus.PENDING,
            e.nextAttemptAt = NULL
        WHERE e.id = :id
          AND e.status IN ('FAILED', 'DEAD_LETTERED', 'SKIPPED', 'ACKNOWLEDGED', 'IN_FLIGHT')
        """)
    int retryByOperator(@Param("id") UUID id);

    /**
     * Operator-initiated skip: drop the event without applying. Used
     * when the operator decides the change isn't appropriate for the
     * target (e.g. an attribute the target schema rejects).
     */
    @Modifying
    @Query("""
        UPDATE ReplicationEvent e
        SET e.status = com.ldapportal.entity.enums.ReplicationEventStatus.SKIPPED
        WHERE e.id = :id
          AND e.status IN ('PENDING', 'FAILED', 'DEAD_LETTERED')
        """)
    int skipByOperator(@Param("id") UUID id);

    /**
     * Operator-initiated acknowledge: mark a DEAD_LETTERED event as
     * "seen and dismissed". Differs from SKIPPED — acknowledge implies
     * the operator understood the failure and chose not to retry,
     * rather than intentionally discarding the change.
     */
    @Modifying
    @Query("""
        UPDATE ReplicationEvent e
        SET e.status = com.ldapportal.entity.enums.ReplicationEventStatus.ACKNOWLEDGED
        WHERE e.id = :id
          AND e.status = com.ldapportal.entity.enums.ReplicationEventStatus.DEAD_LETTERED
        """)
    int acknowledgeByOperator(@Param("id") UUID id);
}
