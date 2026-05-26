// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.LifecyclePlaybook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LifecyclePlaybookRepository extends JpaRepository<LifecyclePlaybook, UUID> {

    List<LifecyclePlaybook> findAllByDirectoryIdOrderByNameAsc(UUID directoryId);

    List<LifecyclePlaybook> findAllByDirectoryIdAndEnabledTrue(UUID directoryId);

    Optional<LifecyclePlaybook> findByIdAndDirectoryId(UUID id, UUID directoryId);

    boolean existsByDirectoryIdAndName(UUID directoryId, String name);
}
