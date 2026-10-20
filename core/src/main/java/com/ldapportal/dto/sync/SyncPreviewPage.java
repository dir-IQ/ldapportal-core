// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/**
 * One page of a per-entry reconcile preview, ordered by target DN with parents
 * before children (the order a reconcile can create them in).
 *
 * @param entries        the page's entries, each with its predicted action
 * @param page           zero-based page number
 * @param size           page size
 * @param totalEntries   entries in the listing (after the changes-only filter)
 * @param totalPages     pages in the listing
 * @param changesOnly    whether entries certain to be UNCHANGED were left out
 * @param sourceCount    entries enumerated in the source scope
 * @param managedCount   index rows the set currently manages
 * @param unchangedCount entries certain to be UNCHANGED (computed when {@code changesOnly})
 * @param guardTripped   the delete guard would hold every delete for review
 * @param guardReason    why, when {@code guardTripped}
 * @param completeScan   false when source enumeration failed (no deletes would happen)
 * @param note           explanation when the preview couldn't be computed
 */
public record SyncPreviewPage(
        List<SyncPreviewEntry> entries,
        int page,
        int size,
        long totalEntries,
        int totalPages,
        boolean changesOnly,
        int sourceCount,
        long managedCount,
        Integer unchangedCount,
        boolean guardTripped,
        String guardReason,
        boolean completeScan,
        String note) {
}
