// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import java.util.List;

/**
 * Preview result returned before a bulk CSV import is confirmed.
 * Contains the parsed rows with computed DNs but no LDAP writes have occurred.
 *
 * @param unmappedColumns CSV columns the import template doesn't mention, in
 *                        file order. They are not imported (a template-driven
 *                        import never passes unknown columns through as
 *                        attributes). Always empty for template-less imports,
 *                        where every header is used as an attribute name.
 */
public record BulkImportPreviewResult(
        int totalRows,
        List<BulkImportPreviewRow> rows,
        List<String> unmappedColumns) {

    public BulkImportPreviewResult(int totalRows, List<BulkImportPreviewRow> rows) {
        this(totalRows, rows, List.of());
    }
}
