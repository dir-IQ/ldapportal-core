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
 * issues. The frontend uses this to warn users at preview time about rows
 * that would otherwise fail at confirm with {@code OBJECT_CLASS_VIOLATION}.</p>
 *
 * <p>Group imports also fill {@code groupAction} (whether the row creates or
 * updates its group), {@code message} (why a row can't run at all, e.g. a
 * delete against a group that doesn't exist) and {@code memberChanges} (the
 * planned outcome of every member change on the row) and {@code attributeChanges}
 * (the attributes an existing group will get new values for).</p>
 */
public record BulkImportPreviewRow(
        int rowNumber,
        String computedDn,
        Map<String, String> attributes,
        List<String> missingRequired,
        GroupAction groupAction,
        String message,
        List<GroupMemberChange> memberChanges,
        List<GroupAttributeChange> attributeChanges) {

    /**
     * What a group-import row does to its group. {@code null} for user imports
     * and for group rows that can't run (see {@link #message()}).
     */
    public enum GroupAction { CREATE, UPDATE }

    /** User-import (and schema-validated) rows: no group detail. */
    public BulkImportPreviewRow(int rowNumber, String computedDn, Map<String, String> attributes,
                                List<String> missingRequired) {
        this(rowNumber, computedDn, attributes, missingRequired, null, null, List.of(), List.of());
    }

    /** Convenience for callers that aren't running schema validation. */
    public BulkImportPreviewRow(int rowNumber, String computedDn, Map<String, String> attributes) {
        this(rowNumber, computedDn, attributes, List.of());
    }
}
