// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.CsvMappingTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CsvMappingTemplateRepository extends JpaRepository<CsvMappingTemplate, UUID> {

    List<CsvMappingTemplate> findAllByDirectoryId(UUID directoryId);

    boolean existsByDirectoryIdAndName(UUID directoryId, String name);
}
