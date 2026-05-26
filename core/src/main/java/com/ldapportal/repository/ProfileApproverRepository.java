// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ProfileApprover;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ProfileApproverRepository extends JpaRepository<ProfileApprover, UUID> {

    List<ProfileApprover> findAllByProfileId(UUID profileId);

    @Query("SELECT pa FROM ProfileApprover pa JOIN FETCH pa.adminAccount WHERE pa.profile.id = :profileId")
    List<ProfileApprover> findAllByProfileIdWithAccount(UUID profileId);

    boolean existsByProfileIdAndAdminAccountId(UUID profileId, UUID adminAccountId);

    void deleteAllByProfileId(UUID profileId);
}
