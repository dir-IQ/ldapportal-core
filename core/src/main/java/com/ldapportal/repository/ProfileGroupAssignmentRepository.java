// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ProfileGroupAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProfileGroupAssignmentRepository extends JpaRepository<ProfileGroupAssignment, UUID> {

    List<ProfileGroupAssignment> findAllByProfileIdOrderByDisplayOrderAsc(UUID profileId);

    void deleteAllByProfileId(UUID profileId);
}
