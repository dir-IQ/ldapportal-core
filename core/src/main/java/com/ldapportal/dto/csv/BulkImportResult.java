// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import java.util.List;

/**
 * Summary result returned after a bulk CSV import completes.
 * All rows are processed; errors in individual rows do not abort the import.
 */
public record BulkImportResult(
        int totalRows,
        long created,
        long updated,
        long skipped,
        long errors,
        List<BulkImportRowResult> rows) {
}
