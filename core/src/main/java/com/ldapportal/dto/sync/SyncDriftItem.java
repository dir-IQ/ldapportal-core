// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.enums.SyncDriftCategory;

import java.util.List;

/**
 * One disagreement a content verify found between a sync set's source and target.
 *
 * @param category   missing on the target, orphaned on the target, or content drift
 * @param targetDn   the entry's (expected, for MISSING) target DN
 * @param sourceDn   the source member it comes from; null for an ORPHAN
 * @param identity   the member's identity; null for an ORPHAN
 * @param attributes the attributes that differ (MISMATCH only; empty otherwise)
 */
public record SyncDriftItem(SyncDriftCategory category, String targetDn, String sourceDn, String identity,
                            List<AttributeDiff> attributes) {

    /**
     * One differing attribute: the values the projected source wants and the
     * values the target has. Secret attributes show neither (see
     * {@code hidden}); long value lists are cut at
     * {@code SyncContentVerifier.VALUE_LIMIT} ({@code truncated}).
     */
    public record AttributeDiff(String attribute, List<String> expected, List<String> actual,
                                boolean hidden, boolean truncated) {
    }
}
