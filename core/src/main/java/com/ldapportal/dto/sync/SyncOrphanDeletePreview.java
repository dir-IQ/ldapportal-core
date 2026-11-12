// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.util.List;

/** What deleting the chosen orphans would do, each re-checked live just now. */
public record SyncOrphanDeletePreview(List<Item> items) {

    public enum Action { DELETE, SKIP }

    /**
     * @param reason  for a SKIP, why the entry is left alone
     * @param beneath for a DELETE, how many entries are beneath it on the
     *                target: its delete fails while any remain, unless the
     *                operator chooses to delete them too
     */
    public record Item(int seq, Action action, String targetDn, String reason, int beneath) {
        public Item(int seq, Action action, String targetDn, String reason) {
            this(seq, action, targetDn, reason, 0);
        }
    }
}
