// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

/**
 * Outcome for a single CSV data row during a bulk import.
 */
public record BulkImportRowResult(
        int rowNumber,
        String dn,
        Status status,
        String message) {

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
