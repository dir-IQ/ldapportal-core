// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import com.ldapportal.entity.enums.ConflictHandling;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for a saved CSV mapping template, including its column entries.
 */
public record CsvMappingTemplateDto(
        UUID id,
        UUID directoryId,
        String name,
        String objectClass,
        String targetKeyAttribute,
        ConflictHandling conflictHandling,
        boolean skipHeaderRow,
        List<CsvColumnMappingDto> entries,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
