// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.RegistrationRequest;
import com.ldapportal.entity.enums.RegistrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface RegistrationRequestRepository extends JpaRepository<RegistrationRequest, UUID> {

    Optional<RegistrationRequest> findByVerificationToken(String token);

    Optional<RegistrationRequest> findByIdAndEmail(UUID id, String email);

    Optional<RegistrationRequest> findByPendingApprovalId(UUID pendingApprovalId);

    boolean existsByEmailAndProfileIdAndStatusIn(String email, UUID profileId,
                                                  Collection<RegistrationStatus> statuses);
}
