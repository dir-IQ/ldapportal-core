// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/** What an orphan delete did, item by item, with totals. */
public record SyncOrphanDeleteResult(int deleted, int skipped, int failed, List<Item> items) {

    public enum Outcome {
        /** Deleted from the target. */
        DELETED,
        /** Left alone: the re-check found it isn't provably this set's orphan (see message). */
        SKIPPED,
        /** The delete failed (e.g. the entry has children). */
        FAILED
    }

    public record Item(int seq, Outcome outcome, String targetDn, String message) {
    }
}
