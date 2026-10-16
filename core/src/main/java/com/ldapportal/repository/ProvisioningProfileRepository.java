// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ProvisioningProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProvisioningProfileRepository extends JpaRepository<ProvisioningProfile, UUID> {

    List<ProvisioningProfile> findAllByDirectoryIdOrderByNameAsc(UUID directoryId);

    List<ProvisioningProfile> findAllByOrderByDirectoryIdAscNameAsc();

    Optional<ProvisioningProfile> findByIdAndDirectoryId(UUID id, UUID directoryId);

    List<ProvisioningProfile> findAllByDirectoryIdAndEnabledTrue(UUID directoryId);

    List<ProvisioningProfile> findAllByDirectoryIdAndSelfRegistrationAllowedTrue(UUID directoryId);

    /**
     * Whether any profile allows self-registration — the self-registration
     * half of {@link com.ldapportal.service.ApprovalUsageService}'s "can any
     * approval source produce requests?" check. Global, directory-set and
     * single-directory variants mirror {@code ProfileApprovalConfigRepository}.
     */
    boolean existsBySelfRegistrationAllowedTrue();

    boolean existsBySelfRegistrationAllowedTrueAndDirectory_IdIn(Collection<UUID> directoryIds);

    boolean existsBySelfRegistrationAllowedTrueAndDirectory_Id(UUID directoryId);

    boolean existsByDirectoryIdAndName(UUID directoryId, String name);

    /** Stable IaC key lookup — the slug is globally unique. */
    Optional<ProvisioningProfile> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<ProvisioningProfile> findAllByDirectoryIdAndAutoIncludeGroupsTrue(UUID directoryId);

    /**
     * Used by the Phase 7 usage-limit check on profile creation —
     * matches the license's PROFILES_PER_DIRECTORY cap.
     */
    long countByDirectoryId(UUID directoryId);
}
