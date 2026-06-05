// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import java.util.List;

/**
 * Preview result returned before a bulk delete is confirmed. Every CSV row is
 * resolved and classified (see {@link BulkDeletePreviewRow.Disposition}); no
 * LDAP writes have occurred.
 */
public record BulkDeletePreviewResult(
        int totalRows,
        List<BulkDeletePreviewRow> rows) {
}
