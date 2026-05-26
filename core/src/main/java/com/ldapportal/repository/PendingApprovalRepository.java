// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.enums.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PendingApprovalRepository extends JpaRepository<PendingApproval, UUID> {

    List<PendingApproval> findAllByProfileIdAndStatus(UUID profileId, ApprovalStatus status);

    List<PendingApproval> findAllByDirectoryIdAndStatus(UUID directoryId, ApprovalStatus status);

    List<PendingApproval> findAllByDirectoryIdOrderByCreatedAtDesc(UUID directoryId);

    List<PendingApproval> findAllByRequestedByOrderByCreatedAtDesc(UUID requestedBy);

    long countByProfileIdAndStatus(UUID profileId, ApprovalStatus status);

    long countByDirectoryIdAndStatus(UUID directoryId, ApprovalStatus status);

    List<PendingApproval> findAllByStatus(ApprovalStatus status);

    long countByStatus(ApprovalStatus status);
}
