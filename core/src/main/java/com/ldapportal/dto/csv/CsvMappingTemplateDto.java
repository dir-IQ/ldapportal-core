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
        /** When set, the DN is read from this CSV column instead of constructed from RDN + parent DN. */
        String dnSourceColumn,
        List<CsvColumnMappingDto> entries,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
