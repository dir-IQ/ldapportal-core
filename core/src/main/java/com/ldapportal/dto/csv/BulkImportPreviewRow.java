// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import java.util.List;
import java.util.Map;

/**
 * A single preview row showing the computed DN and attribute values
 * that would be written during a bulk import.
 *
 * <p>{@code missingRequired} lists LDAP attributes that the row is missing
 * but the template's object classes mark as MUST. Empty for rows with no
 * issues, and always empty for group imports (group preview doesn't run
 * schema validation — group columns are fixed). The frontend uses this
 * to warn users at preview time about rows that would otherwise fail at
 * confirm with {@code OBJECT_CLASS_VIOLATION}.</p>
 */
public record BulkImportPreviewRow(
        int rowNumber,
        String computedDn,
        Map<String, String> attributes,
        List<String> missingRequired) {

    /** Convenience for callers that aren't running schema validation. */
    public BulkImportPreviewRow(int rowNumber, String computedDn, Map<String, String> attributes) {
        this(rowNumber, computedDn, attributes, List.of());
    }
}
