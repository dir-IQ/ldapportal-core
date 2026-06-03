// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

/**
 * Headline result of computing an LDIF preview: the {@code previewId} the
 * client uses to page/filter/apply, totals by operation, warning/error
 * counts, and the first page of rows so the UI renders without a second
 * round-trip.
 *
 * @param previewId    cache key for subsequent page/row/apply calls
 * @param totalRows    total records parsed (incl. unparseable)
 * @param countsByOp   per-operation totals
 * @param warningCount rows carrying at least one WARNING issue
 * @param errorCount   rows that failed to parse or carry an ERROR issue
 * @param truncated    true if the upload was capped (reserved; always false in v1)
 * @param page0        first page of rows (problems-first ordering applied)
 */
public record LdifPreviewSummary(
        String previewId,
        int totalRows,
        OpCounts countsByOp,
        int warningCount,
        int errorCount,
        boolean truncated,
        LdifPreviewPage page0) {

    /** Per-operation row totals across the whole preview. */
    public record OpCounts(int add, int modify, int delete, int moddn, int skip, int error) {}
}
