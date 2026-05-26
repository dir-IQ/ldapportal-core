// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ProfileAttributeConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProfileAttributeConfigRepository extends JpaRepository<ProfileAttributeConfig, UUID> {

    List<ProfileAttributeConfig> findAllByProfileIdOrderByDisplayOrderAsc(UUID profileId);

    void deleteAllByProfileId(UUID profileId);
}
