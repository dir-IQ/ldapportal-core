// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/**
 * What repairing the chosen drift items would do, each re-checked live just
 * now: create a missing entry, re-apply a drifted one, or skip it and why.
 */
public record SyncDriftRepairPreview(List<Item> items) {

    /** What a repair would do to one item. */
    public enum Action { CREATE, REAPPLY, SKIP }

    /**
     * @param targetDn   where the entry is (or would be created), as re-checked
     * @param attributes for a REAPPLY, the attributes that differ now
     * @param reason     for a SKIP, why
     */
    public record Item(int seq, Action action, String targetDn, String sourceDn,
                       List<SyncDriftItem.AttributeDiff> attributes, String reason) {
    }
}
