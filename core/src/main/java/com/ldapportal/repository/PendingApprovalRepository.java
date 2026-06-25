// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.enums.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
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

    /**
     * Pending-approval counts for a set of profiles in one query, as
     * {@code [profileId (UUID), count (Long)]} rows. Replaces a per-profile
     * {@code countByProfileIdAndStatus} loop on the dashboard. Profiles with no
     * pending approvals are simply absent from the result — callers default
     * them to zero. Guard against an empty {@code profileIds}: JPQL {@code IN ()}
     * is invalid, so don't call this with an empty collection.
     */
    @Query("SELECT pa.profileId, COUNT(pa) FROM PendingApproval pa "
            + "WHERE pa.status = :status AND pa.profileId IN :profileIds "
            + "GROUP BY pa.profileId")
    List<Object[]> countPendingByProfile(@Param("status") ApprovalStatus status,
                                         @Param("profileIds") Collection<UUID> profileIds);

    /**
     * Pending-approval counts for a set of directories in one query, as
     * {@code [directoryId (UUID), count (Long)]} rows. See
     * {@link #countPendingByProfile} — same shape and the same empty-{@code IN}
     * caveat.
     */
    @Query("SELECT pa.directoryId, COUNT(pa) FROM PendingApproval pa "
            + "WHERE pa.status = :status AND pa.directoryId IN :directoryIds "
            + "GROUP BY pa.directoryId")
    List<Object[]> countPendingByDirectory(@Param("status") ApprovalStatus status,
                                           @Param("directoryIds") Collection<UUID> directoryIds);

    List<PendingApproval> findAllByStatus(ApprovalStatus status);

    long countByStatus(ApprovalStatus status);

    /** Oldest approval in a given status (e.g. PENDING backlog age); null when none. */
    @Query("select min(a.createdAt) from PendingApproval a where a.status = :status")
    OffsetDateTime findOldestCreatedAtByStatus(@Param("status") ApprovalStatus status);
}
