// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/** What deleting the chosen orphans would do, each re-checked live just now. */
public record SyncOrphanDeletePreview(List<Item> items) {

    public enum Action { DELETE, SKIP }

    /** @param reason for a SKIP, why the entry is left alone */
    public record Item(int seq, Action action, String targetDn, String reason) {
    }
}
