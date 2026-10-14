// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import java.util.List;

/**
 * Outcome for a single CSV data row during a bulk import.
 */
public record BulkImportRowResult(
        int rowNumber,
        String dn,
        Status status,
        String message,
        List<GroupMemberChange> memberChanges) {

    /** Rows without member detail (user imports, and group rows that never got that far). */
    public BulkImportRowResult(int rowNumber, String dn, Status status, String message) {
        this(rowNumber, dn, status, message, List.of());
    }

    public enum Status {
        CREATED,
        UPDATED,
        SKIPPED,
        ERROR
    }

    public static BulkImportRowResult created(int row, String dn) {
        return new BulkImportRowResult(row, dn, Status.CREATED, null);
    }

    public static BulkImportRowResult updated(int row, String dn) {
        return new BulkImportRowResult(row, dn, Status.UPDATED, null);
    }

    public static BulkImportRowResult skipped(int row, String dn, String reason) {
        return new BulkImportRowResult(row, dn, Status.SKIPPED, reason);
    }

    public static BulkImportRowResult error(int row, String dn, String message) {
        return new BulkImportRowResult(row, dn, Status.ERROR, message);
    }
}
