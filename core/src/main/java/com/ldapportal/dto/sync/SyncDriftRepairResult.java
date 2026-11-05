// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/** What a drift repair did, item by item, with totals. */
public record SyncDriftRepairResult(int created, int reapplied, int alreadyInSync, int skipped, int held,
                                    int failed, List<Item> items) {

    public enum Outcome {
        /** The missing entry was created on the target. */
        CREATED,
        /** The target entry was brought back to the projected source. */
        REAPPLIED,
        /** The target already matched when the repair ran: nothing written. */
        ALREADY_IN_SYNC,
        /** Not repaired: the re-check found it no longer drifts in a way this can fix (see message). */
        SKIPPED,
        /** The sync held it for review instead (a placement conflict or ambiguous match). */
        HELD,
        /** The write failed, or the set is disabled. */
        FAILED
    }

    public record Item(int seq, Outcome outcome, String targetDn, String message) {
    }
}
