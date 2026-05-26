// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.AdminProfileRole;
import com.ldapportal.entity.enums.BaseRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface AdminProfileRoleRepository extends JpaRepository<AdminProfileRole, UUID> {

    Optional<AdminProfileRole> findByAdminAccountIdAndProfileId(UUID adminAccountId, UUID profileId);

    List<AdminProfileRole> findAllByAdminAccountId(UUID adminAccountId);

    List<AdminProfileRole> findAllByProfileId(UUID profileId);

    boolean existsByAdminAccountIdAndProfileId(UUID adminAccountId, UUID profileId);

    boolean existsByAdminAccountIdAndProfileDirectoryId(UUID adminAccountId, UUID directoryId);

    List<AdminProfileRole> findAllByProfileDirectoryId(UUID directoryId);

    void deleteByAdminAccountIdAndProfileId(UUID adminAccountId, UUID profileId);

    List<AdminProfileRole> findAllByAdminAccountIdAndProfileDirectoryId(UUID adminAccountId, UUID directoryId);

    @Query("SELECT r FROM AdminProfileRole r JOIN FETCH r.profile WHERE r.adminAccount.id = :adminId AND r.profile.directory.id = :dirId")
    List<AdminProfileRole> findAllByAdminAccountIdAndProfileDirectoryIdWithProfile(@Param("adminId") UUID adminAccountId, @Param("dirId") UUID directoryId);

    boolean existsByAdminAccountIdAndBaseRole(UUID adminAccountId, BaseRole baseRole);

    @Query("SELECT DISTINCT r.profile.directory.id FROM AdminProfileRole r WHERE r.adminAccount.id = :adminId")
    Set<UUID> findDistinctDirectoryIdsByAdminAccountId(@Param("adminId") UUID adminAccountId);
}
