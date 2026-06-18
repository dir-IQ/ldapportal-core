// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import com.ldapportal.entity.enums.ConflictHandling;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for creating or replacing a {@link com.ldapportal.entity.CsvMappingTemplate}.
 */
public record CreateCsvMappingTemplateRequest(
        @NotBlank String name,
        /** LDAP objectClass whose attributes define the column mappings. */
        String objectClass,
        /** LDAP attribute used to match CSV rows against existing directory entries (default: uid). */
        String targetKeyAttribute,
        /** Default conflict resolution when a matching entry already exists. */
        ConflictHandling conflictHandling,
        /** Whether to treat the first CSV row as headers (true, default) or data (false). */
        Boolean skipHeaderRow,
        /**
         * Optional. When set, the importer reads each entry's full DN from this CSV
         * column instead of constructing it from the RDN attribute + parent DN.
         * Blank/null keeps the default construct-from-RDN behaviour.
         */
        String dnSourceColumn,
        @NotNull @Valid List<CsvColumnMappingDto> entries) {
}
