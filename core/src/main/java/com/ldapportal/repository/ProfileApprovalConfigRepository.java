// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ProfileApprovalConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ProfileApprovalConfigRepository extends JpaRepository<ProfileApprovalConfig, UUID> {

    Optional<ProfileApprovalConfig> findByProfileId(UUID profileId);

    void deleteByProfileId(UUID profileId);

    /**
     * True if any profile anywhere has {@code requireApproval = true}. Used by
     * the superadmin dashboard to decide whether approval-related UI (the
     * Pending Approvals metric card, the Approval Aging panel, the sidebar
     * nav link) is worth surfacing at all.
     */
    boolean existsByRequireApprovalTrue();

    /**
     * True if any profile in one of {@code directoryIds} has
     * {@code requireApproval = true}. The admin-scoped variant of the flag
     * above — the dashboard restricts this to the caller's authorized
     * directories so an admin doesn't see approval UI just because some
     * other directory uses approvals.
     */
    boolean existsByRequireApprovalTrueAndProfile_Directory_IdIn(Collection<UUID> directoryIds);

    /**
     * True if any profile in the given directory has
     * {@code requireApproval = true}. Used for the per-directory sidebar
     * nav link in the admin layout — each directory gets its own
     * configured/not-configured verdict.
     */
    boolean existsByRequireApprovalTrueAndProfile_Directory_Id(UUID directoryId);
}
