// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import java.util.List;

/**
 * Summary result returned after a bulk delete completes. All rows are
 * processed; errors in individual rows do not abort the operation.
 */
public record BulkDeleteResult(
        int totalRows,
        long deleted,
        long skipped,
        long errors,
        List<BulkDeleteRowResult> rows) {
}
