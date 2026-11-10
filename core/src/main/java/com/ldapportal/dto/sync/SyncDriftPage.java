// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.enums.SyncDriftCategory;
import com.ldapportal.entity.enums.SyncHeldStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * One page of a sync set's drift items, from its last complete content verify
 * (only the latest is kept).
 *
 * @param verifiedAt      when that verify ran; null if the set hasn't had a complete one
 * @param incompleteAt    a later verify that couldn't read both sides; the items are from the earlier one
 * @param detailsRecorded false when the counts on the set predate stored items (a verify
 *                        from before items were kept): verifying again records them
 * @param counts          stored items per category
 * @param matched         items matching the category and search
 * @param entries         this page of them, in verify order
 * @param held            stored held references per status (not drift; listed by
 *                        {@code GET …/drift/held})
 */
public record SyncDriftPage(OffsetDateTime verifiedAt, OffsetDateTime incompleteAt, boolean detailsRecorded,
                            Map<SyncDriftCategory, Long> counts, long matched, int page, int size,
                            List<Entry> entries, Map<SyncHeldStatus, Long> held) {

    /** As the canonical constructor, with no held references counted. */
    public SyncDriftPage(OffsetDateTime verifiedAt, OffsetDateTime incompleteAt, boolean detailsRecorded,
                         Map<SyncDriftCategory, Long> counts, long matched, int page, int size,
                         List<Entry> entries) {
        this(verifiedAt, incompleteAt, detailsRecorded, counts, matched, page, size, entries, Map.of());
    }

    /** One item with its position, so the table has a stable row key. */
    public record Entry(int seq, SyncDriftCategory category, String targetDn, String sourceDn, String identity,
                        List<SyncDriftItem.AttributeDiff> attributes) {
    }
}
