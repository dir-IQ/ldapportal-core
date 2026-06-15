// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import com.ldapportal.entity.enums.ConflictHandling;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.UUID;

/**
 * Parameters for a bulk CSV user import operation.
 *
 * <p>Column-to-attribute mapping is resolved in priority order:
 * <ol>
 *   <li>{@code columnMappings} on this request (explicit ad-hoc override)</li>
 *   <li>Entries of the referenced {@code templateId}</li>
 *   <li>CSV header names used as LDAP attribute names directly (passthrough)</li>
 * </ol>
 * </p>
 *
 * <p>When {@code templateId} is supplied and neither {@code targetKeyAttribute}
 * nor {@code conflictHandling} are set on this request, the template's values
 * are used as defaults.</p>
 */
public record BulkImportRequest(
        /** Optional saved template; drives column mapping, key attribute, and conflict handling. */
        UUID templateId,
        /** DN of the container under which new entries are created (e.g. {@code ou=people,dc=example,dc=com}). */
        @NotBlank String parentDn,
        /** LDAP attribute whose value forms the RDN of new entries. Overrides template default. */
        String targetKeyAttribute,
        /** How to handle rows whose key value already exists in the directory. Overrides template default. */
        ConflictHandling conflictHandling,
        /** Whether to treat the first CSV row as headers (true, default) or data (false). */
        Boolean skipHeaderRow,
        /** Ad-hoc column mappings. When non-empty these override any template entries. */
        @Valid List<CsvColumnMappingDto> columnMappings) {
}
