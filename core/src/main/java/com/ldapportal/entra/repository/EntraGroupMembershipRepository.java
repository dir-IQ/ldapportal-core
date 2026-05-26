// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra.repository;

import com.ldapportal.entra.entity.EntraGroupMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface EntraGroupMembershipRepository extends JpaRepository<EntraGroupMembership, UUID> {

    List<EntraGroupMembership> findAllByDirectoryIdAndUserObjectId(UUID directoryId, String userObjectId);

    List<EntraGroupMembership> findAllByDirectoryIdAndGroupObjectId(UUID directoryId, String groupObjectId);

    long countByDirectoryId(UUID directoryId);

    long countByDirectoryIdAndGroupObjectId(UUID directoryId, String groupObjectId);

    @Modifying
    @Query("DELETE FROM EntraGroupMembership m WHERE m.directoryId = :directoryId")
    void deleteAllByDirectoryId(UUID directoryId);

    @Modifying
    @Query("DELETE FROM EntraGroupMembership m WHERE m.directoryId = :directoryId AND m.groupObjectId = :groupObjectId")
    void deleteAllByDirectoryIdAndGroupObjectId(UUID directoryId, String groupObjectId);
}
