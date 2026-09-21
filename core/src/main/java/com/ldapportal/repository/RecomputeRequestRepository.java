// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.entity.RecomputeRequestId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for the {@link RecomputeRequest} coalescing queue.
 *
 * <p>The worker drains via a claim/settle lease so a trigger that arrives for an
 * already-claimed key isn't lost: claiming stamps {@code claimed_at}; the request
 * is removed only if it is <em>still</em> claimed; a re-enqueue nulls
 * {@code claimed_at} to force reprocessing; and a stale-claim sweep reclaims rows
 * orphaned by a crashed worker. The lease keys off "is it still claimed" (not a
 * timestamp comparison) so it's immune to DB timestamp-precision truncation.
 */
public interface RecomputeRequestRepository extends JpaRepository<RecomputeRequest, RecomputeRequestId> {

    List<RecomputeRequest> findAllBySyncSetId(UUID syncSetId);

    @Query("select r from RecomputeRequest r where r.claimedAt is null order by r.enqueuedAt asc")
    List<RecomputeRequest> findBatchUnclaimed(Pageable pageable);

    /** Atomically claim an unclaimed request. Returns 1 if claimed, 0 if lost the race. */
    @Transactional
    @Modifying
    @Query("update RecomputeRequest r set r.claimedAt = :now "
            + "where r.syncSetId = :syncSetId and r.requestKey = :key and r.claimedAt is null")
    int claim(@Param("syncSetId") UUID syncSetId, @Param("key") String key,
              @Param("now") OffsetDateTime now);

    /** Remove a processed request only if it is still claimed (no re-trigger landed). */
    @Transactional
    @Modifying
    @Query("delete from RecomputeRequest r "
            + "where r.syncSetId = :syncSetId and r.requestKey = :key and r.claimedAt is not null")
    void deleteIfClaimed(@Param("syncSetId") UUID syncSetId, @Param("key") String key);

    /**
     * Re-open an existing request for a key: null the claim so a worker mid-process
     * cannot settle it (its delete-if-still-claimed misses) and the newer source
     * state is reprocessed. A row-count update, never an entity write, so a row the
     * worker deletes concurrently yields 0 instead of a stale-state exception.
     * Returns 1 when a row existed, 0 when the caller must insert one.
     */
    @Transactional
    @Modifying
    @Query("update RecomputeRequest r set r.claimedAt = null "
            + "where r.syncSetId = :syncSetId and r.requestKey = :key")
    int reopen(@Param("syncSetId") UUID syncSetId, @Param("key") String key);

    /** Keep the highest source cursor seen for a key (behind-cursor triggers leave it alone). */
    @Transactional
    @Modifying
    @Query("update RecomputeRequest r set r.srcCursor = :cursor "
            + "where r.syncSetId = :syncSetId and r.requestKey = :key "
            + "and (r.srcCursor is null or r.srcCursor < :cursor)")
    int bumpCursor(@Param("syncSetId") UUID syncSetId, @Param("key") String key,
                   @Param("cursor") long cursor);

    /** Release a claim so the request is retried (on unexpected processing fault). */
    @Transactional
    @Modifying
    @Query("update RecomputeRequest r set r.claimedAt = null "
            + "where r.syncSetId = :syncSetId and r.requestKey = :key")
    void releaseClaim(@Param("syncSetId") UUID syncSetId, @Param("key") String key);

    /** Reclaim rows orphaned by a worker that crashed between claim and settle. */
    @Transactional
    @Modifying
    @Query("update RecomputeRequest r set r.claimedAt = null "
            + "where r.claimedAt is not null and r.claimedAt < :threshold")
    int releaseStaleClaims(@Param("threshold") OffsetDateTime threshold);

    // ── Observability (read-only aggregates) ────────────────────────────────────

    /** Queue depth: requests still waiting for a worker. */
    long countByClaimedAtIsNull();

    /** In-flight: requests currently claimed by a worker. */
    long countByClaimedAtIsNotNull();

    /** Oldest waiting request's enqueue time (queue lag); null when the queue is empty. */
    @Query("select min(r.enqueuedAt) from RecomputeRequest r where r.claimedAt is null")
    OffsetDateTime findOldestUnclaimedEnqueuedAt();
}
