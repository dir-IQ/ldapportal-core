// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra.repository;

import com.ldapportal.entra.entity.EntraUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EntraUserRepository extends JpaRepository<EntraUser, UUID> {

    List<EntraUser> findAllByDirectoryId(UUID directoryId);

    Optional<EntraUser> findByDirectoryIdAndEntraObjectId(UUID directoryId, String entraObjectId);

    long countByDirectoryId(UUID directoryId);

    @Query("SELECT u FROM EntraUser u WHERE u.directoryId = :directoryId " +
           "AND u.userPrincipalName LIKE '%#EXT#%' AND u.syncedAt > :since")
    List<EntraUser> findGuestsSyncedAfter(UUID directoryId, java.time.OffsetDateTime since);

    @Modifying
    @Query("DELETE FROM EntraUser u WHERE u.directoryId = :directoryId")
    void deleteAllByDirectoryId(UUID directoryId);
}
