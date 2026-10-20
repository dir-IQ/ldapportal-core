// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;
import java.util.UUID;

/**
 * One page of a completed per-entry reconcile preview, ordered by target DN
 * with parents before children (the order a reconcile can create them in).
 * Every page of a run reads the same stored plan; the run's summary is in
 * {@link SyncPreviewStatus}.
 *
 * @param entries      the page's entries, each with its predicted action
 * @param page         zero-based page actually returned (a page past the end
 *                     returns the last page)
 * @param size         page size
 * @param totalEntries entries in the listing (after the changes-only filter)
 * @param totalPages   pages in the listing
 * @param changesOnly  whether entries that wouldn't change were left out
 * @param runId        the run these entries belong to
 */
public record SyncPreviewPage(
        List<SyncPreviewEntry> entries,
        int page,
        int size,
        long totalEntries,
        int totalPages,
        boolean changesOnly,
        UUID runId) {
}
