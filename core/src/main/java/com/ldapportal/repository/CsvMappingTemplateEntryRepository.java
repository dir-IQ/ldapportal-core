// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.CsvMappingTemplateEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CsvMappingTemplateEntryRepository extends JpaRepository<CsvMappingTemplateEntry, UUID> {

    List<CsvMappingTemplateEntry> findAllByTemplateId(UUID templateId);

    Optional<CsvMappingTemplateEntry> findByTemplateIdAndCsvColumnName(UUID templateId, String csvColumnName);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM CsvMappingTemplateEntry e WHERE e.template.id = :templateId")
    void deleteAllByTemplateId(UUID templateId);
}
