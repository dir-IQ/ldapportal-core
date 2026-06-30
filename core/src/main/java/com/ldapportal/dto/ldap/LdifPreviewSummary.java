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
 * @param userAddCount number of new user-entry adds that will be routed through
 *                     the provisioning SPI (candidates for vendor account
 *                     provisioning, e.g. ISVA secUser). Intrinsic count — does
 *                     not subtract the file-level suppression below.
 * @param containsVendorOverlayEntries true when the upload itself already
 *                     contains vendor-overlay entries (objectClass {@code secUser});
 *                     in that case the importer writes everything as-is and does
 *                     NOT layer a fresh overlay, so provisioning is suppressed
 *                     for the whole import regardless of the operator toggle.
 * @param applicableCount records an apply will actually attempt: actionable ops
 *                     (add / modify / delete / moddn) carrying no blocking
 *                     {@code ERROR} issue. Drives the Import button's count;
 *                     {@code 0} means nothing in the file is importable as-is.
 * @param outOfScopeCount records whose DN falls outside {@code baseDn} — a
 *                     blocking {@code OUT_OF_SCOPE} error. Surfaced so the UI can
 *                     explain the base-DN mismatch and the re-base fix rather than
 *                     presenting them as clean adds.
 * @param baseDn       the directory's configured base DN, for the out-of-scope copy.
 */
public record LdifPreviewSummary(
        String previewId,
        int totalRows,
        OpCounts countsByOp,
        int warningCount,
        int errorCount,
        boolean truncated,
        LdifPreviewPage page0,
        int userAddCount,
        boolean containsVendorOverlayEntries,
        int applicableCount,
        int outOfScopeCount,
        String baseDn) {

    /** Per-operation row totals across the whole preview. */
    public record OpCounts(int add, int modify, int delete, int moddn, int skip, int error) {}
}
