// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.DirectoryConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DirectoryConnectionRepository extends JpaRepository<DirectoryConnection, UUID> {

    List<DirectoryConnection> findAllByEnabledTrue();

    Optional<DirectoryConnection> findByUserRepositoryTrue();

    List<DirectoryConnection> findAllByAuditDataSourceId(UUID auditDataSourceId);
}
