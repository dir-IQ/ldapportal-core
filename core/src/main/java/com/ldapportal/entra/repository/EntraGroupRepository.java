// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra.repository;

import com.ldapportal.entra.entity.EntraGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EntraGroupRepository extends JpaRepository<EntraGroup, UUID> {

    List<EntraGroup> findAllByDirectoryId(UUID directoryId);

    Optional<EntraGroup> findByDirectoryIdAndEntraObjectId(UUID directoryId, String entraObjectId);

    long countByDirectoryId(UUID directoryId);

    @Modifying
    @Query("DELETE FROM EntraGroup g WHERE g.directoryId = :directoryId")
    void deleteAllByDirectoryId(UUID directoryId);
}
