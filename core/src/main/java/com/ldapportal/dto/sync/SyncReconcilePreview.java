// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/**
 * Dry-run summary of what a reconcile of a sync set <em>would</em> do, computed
 * without writing to the target. Lets an operator review the blast radius —
 * especially the planned deletions — before enabling a set or applying.
 *
 * @param sourceCount     entries enumerated in the source scope
 * @param managedCount    membership rows the set currently manages
 * @param plannedAdds     in-scope source entries not yet applied to the target
 * @param plannedDeletes  managed entries that would be deleted (scope-exit + not-seen)
 * @param sampleDeleteDns up to a handful of target DNs that would be deleted
 * @param guardTripped    true if the blast-radius / zero-enumeration guard would
 *                        suppress the deletes (quarantine for REVIEW) on a real run
 * @param guardReason     human-readable reason when {@code guardTripped}
 * @param completeScan    false when source enumeration failed (no deletes happen)
 */
public record SyncReconcilePreview(
        int sourceCount,
        long managedCount,
        int plannedAdds,
        int plannedDeletes,
        List<String> sampleDeleteDns,
        boolean guardTripped,
        String guardReason,
        boolean completeScan) {
}
