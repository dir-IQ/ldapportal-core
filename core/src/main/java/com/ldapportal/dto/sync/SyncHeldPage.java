// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.enums.SyncHeldStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * One page of a sync set's held references, from its last complete content
 * verify (only the latest is kept).
 *
 * @param verifiedAt      when that verify ran; null if the set hasn't had a complete one
 * @param detailsRecorded false when the set's held count predates stored rows (a verify
 *                        from before they were kept): verifying again records them
 * @param counts          stored references per status (every status present)
 * @param matched         references matching the status and search
 * @param entries         this page of them, in verify order
 */
public record SyncHeldPage(OffsetDateTime verifiedAt, boolean detailsRecorded, Map<SyncHeldStatus, Long> counts,
                           long matched, int page, int size, List<Entry> entries) {

    /** One reference with its position, so the table has a stable row key. */
    public record Entry(int seq, String entryDn, String sourceDn, String identity, String attribute,
                        String reference, SyncHeldStatus status) {
    }
}
