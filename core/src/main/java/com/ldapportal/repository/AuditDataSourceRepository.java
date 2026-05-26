// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.AuditDataSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditDataSourceRepository extends JpaRepository<AuditDataSource, UUID> {

    List<AuditDataSource> findAllByEnabledTrue();
}
