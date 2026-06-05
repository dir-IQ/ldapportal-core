// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

/**
 * Outcome for a single CSV data row during a bulk delete. All rows are
 * processed regardless of individual failures; the caller receives the full
 * per-row list alongside the summary counts.
 */
public record BulkDeleteRowResult(
        int rowNumber,
        String dn,
        Status status,
        String message) {

    public enum Status {
        DELETED,
        SKIPPED,
        ERROR
    }

    public static BulkDeleteRowResult deleted(int row, String dn) {
        return new BulkDeleteRowResult(row, dn, Status.DELETED, null);
    }

    public static BulkDeleteRowResult skipped(int row, String dn, String reason) {
        return new BulkDeleteRowResult(row, dn, Status.SKIPPED, reason);
    }

    public static BulkDeleteRowResult error(int row, String dn, String message) {
        return new BulkDeleteRowResult(row, dn, Status.ERROR, message);
    }
}
