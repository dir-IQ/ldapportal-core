// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncReconcileRun;

/**
 * A running reconcile's outcome counts at one moment: what the worker
 * publishes after each entry, for the progress write to record while the run
 * is still going.
 */
public record SyncRunCounts(int added, int modified, int moved, int deleted, int heldForReview, int noChange,
                            int forgotten, int unchanged, int failed, int faulted, int skipped, int notInSet) {

    public static final SyncRunCounts NONE = new SyncRunCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    static SyncRunCounts of(SyncReconcileRun r) {
        return new SyncRunCounts(r.getAdded(), r.getModified(), r.getMoved(), r.getDeleted(), r.getHeldForReview(),
                r.getNoChange(), r.getForgotten(), r.getUnchanged(), r.getFailed(), r.getFaulted(), r.getSkipped(),
                r.getNotInSet());
    }
}
