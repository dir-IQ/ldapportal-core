// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.entity.RecomputeRequestId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for the {@link RecomputeRequest} coalescing queue. The worker
 * drains the oldest requests in batches and removes each once processed.
 */
public interface RecomputeRequestRepository extends JpaRepository<RecomputeRequest, RecomputeRequestId> {

    List<RecomputeRequest> findAllBySyncSetId(UUID syncSetId);

    @Query("select r from RecomputeRequest r order by r.enqueuedAt asc")
    List<RecomputeRequest> findBatch(Pageable pageable);

    @Transactional
    @Modifying
    @Query("delete from RecomputeRequest r where r.syncSetId = ?1 and r.requestKey = ?2")
    void deleteByKey(UUID syncSetId, String requestKey);
}
