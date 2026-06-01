// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ReconciliationFinding;
import com.ldapportal.entity.enums.ReconciliationFindingStatus;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
