// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/**
 * What deleting a sync set would discard, for the confirmation shown before
 * the delete. Deleting removes the set's index, history and queued work; the
 * entries it wrote stay on the target, unmanaged.
 *
 * @param name             the set's name
 * @param managed          index rows (target entries the set manages, any state)
 * @param pendingReview    rows held for review (e.g. quarantined deletes) that
 *                         would be discarded undecided
 * @param reconcileRuns    recorded reconcile runs that would be deleted
 * @param otherSets        names of the other sync sets: DN references are
 *                         remapped through every set's index, so a reference
 *                         from one of them to an entry only this set mirrors
 *                         is dropped on that set's next recompute
 */
public record SyncSetDeleteImpact(String name, long managed, long pendingReview, long reconcileRuns,
                                  List<String> otherSets) {
}
