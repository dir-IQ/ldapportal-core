// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import jakarta.validation.constraints.NotBlank;

/**
 * Maps a single CSV column header to an LDAP attribute name.
 *
 * <p>When {@code ignored} is {@code true} the column is present in the CSV
 * file but discarded during import; {@code ldapAttribute} may be {@code null}
 * in that case.</p>
 */
public record CsvColumnMappingDto(
        @NotBlank String csvColumn,
        String ldapAttribute,
        boolean ignored) {
}
