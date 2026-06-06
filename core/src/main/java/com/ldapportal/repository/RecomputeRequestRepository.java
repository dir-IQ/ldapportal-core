// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.entity.RecomputeRequestId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for the {@link RecomputeRequest} coalescing queue. Phase-0
 * mapping only.
 */
public interface RecomputeRequestRepository extends JpaRepository<RecomputeRequest, RecomputeRequestId> {

    List<RecomputeRequest> findAllBySyncSetId(UUID syncSetId);
}
