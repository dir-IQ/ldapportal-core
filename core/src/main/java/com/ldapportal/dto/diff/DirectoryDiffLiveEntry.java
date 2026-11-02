// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.diff;

import com.ldapportal.entity.enums.DirectoryDiffCategory;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A result's entries read again now and compared the run's way.
 *
 * @param category how they compare now ({@code ONLY_A} / {@code ONLY_B} when one side's entry is gone,
 *                 {@code null} when both are)
 * @param dangling DN values of the differing attributes that name no entry in their own directory
 *                 (e.g. a {@code secDN} pointing at a deleted person): checked for up to
 *                 {@code DirectoryDiffService.DANGLING_CHECK_LIMIT} values
 */
public record DirectoryDiffLiveEntry(DirectoryDiffCategory category, String dnA, String dnB, boolean presentInA,
                                     boolean presentInB, OffsetDateTime readAt,
                                     List<DirectoryDiffAttributeDiff> diffs, List<SameAttribute> same,
                                     List<DanglingValue> dangling) {

    /** An attribute equal on both sides, with its values. */
    public record SameAttribute(String name, List<String> values) {
    }

    /** A reference value on {@code side} ("A" or "B") whose DN names no entry in that side's directory. */
    public record DanglingValue(String attribute, String side, String value) {
    }
}
