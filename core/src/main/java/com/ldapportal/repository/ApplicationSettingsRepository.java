// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ApplicationSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApplicationSettingsRepository extends JpaRepository<ApplicationSettings, UUID> {

    /** Returns the singleton settings row, if it exists. */
    Optional<ApplicationSettings> findFirstBy();
}
