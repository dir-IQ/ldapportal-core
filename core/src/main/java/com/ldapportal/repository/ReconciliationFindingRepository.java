// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ReconciliationFinding;
import com.ldapportal.entity.enums.ReconciliationFindingStatus;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReconciliationFindingRepository extends JpaRepository<ReconciliationFinding, UUID> {

    /** Findings for a run with optional status / type filters (null = any) — backs the review list. */
    @Query("""
        SELECT f FROM ReconciliationFinding f
         WHERE f.run.id = :runId
           AND (:status IS NULL OR f.status = :status)
           AND (:type IS NULL OR f.findingType = :type)
         ORDER BY f.findingType, f.targetDn
        """)
    Page<ReconciliationFinding> search(@Param("runId") UUID runId,
                                       @Param("status") ReconciliationFindingStatus status,
                                       @Param("type") ReconciliationFindingType type,
                                       Pageable pageable);

    /** Selected findings within a run (apply/dismiss by id). */
    List<ReconciliationFinding> findByRunIdAndIdIn(UUID runId, Collection<UUID> ids);

    /** All findings of a status within a run (apply-all). */
    List<ReconciliationFinding> findByRunIdAndStatus(UUID runId, ReconciliationFindingStatus status);

    /** Open-finding count for a link — backs the row badge / dashboard awareness. */
    long countByLinkIdAndStatus(UUID linkId, ReconciliationFindingStatus status);

    /**
     * Number of distinct links carrying at least one finding of {@code status}
     * — backs the {@code RECONCILIATION_DRIFT_OPEN} dashboard awareness item
     * ("drift found on N links"). One cheap aggregate rather than per-link
     * counts.
     */
    @Query("""
        SELECT COUNT(DISTINCT f.link.id) FROM ReconciliationFinding f
         WHERE f.status = :status
        """)
    long countDistinctLinksByStatus(@Param("status") ReconciliationFindingStatus status);

    /**
     * Open-finding counts for a batch of links, as {@code [linkId, count]}
     * rows — backs the per-row badge without an N+1 fetch (mirrors
     * {@code ReplicationEventRepository.findHealthRollup}). Links with no
     * open findings are simply absent from the result.
     */
    @Query("""
        SELECT f.link.id, COUNT(f) FROM ReconciliationFinding f
         WHERE f.link.id IN :linkIds
           AND f.status = :status
         GROUP BY f.link.id
        """)
    List<Object[]> countByLinkIdsAndStatus(@Param("linkIds") Collection<UUID> linkIds,
                                           @Param("status") ReconciliationFindingStatus status);

    /**
     * Close out a link's still-open ({@code PROPOSED}) findings as
     * {@code SUPERSEDED} — a newer run has replaced that view of the drift.
     * Called at the start of persisting a fresh run's findings so an
     * un-triaged review-mode link doesn't accumulate duplicate proposals
     * for the same DN run after run (which would also inflate the row badge
     * and the dashboard drift count). Already-resolved findings
     * (AUTO_APPLIED / APPLIED / DISMISSED) are left untouched.
     */
    @Modifying
    @Query("""
        UPDATE ReconciliationFinding f
           SET f.status = com.ldapportal.entity.enums.ReconciliationFindingStatus.SUPERSEDED,
               f.resolvedAt = :now
         WHERE f.link.id = :linkId
           AND f.status = com.ldapportal.entity.enums.ReconciliationFindingStatus.PROPOSED
        """)
    int supersedeOpenForLink(@Param("linkId") UUID linkId, @Param("now") OffsetDateTime now);
}
