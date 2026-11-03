// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.diff;

import com.ldapportal.entity.enums.DirectoryDiffCategory;

import java.util.List;

/**
 * A pair as the run found it: each attribute that differs, and the names of
 * those equal on both sides (their values come from a live re-read).
 */
public record DirectoryDiffEntryDetail(int seq, DirectoryDiffCategory category, String dnA, String dnB,
                                       List<DirectoryDiffAttributeDiff> diffs, List<String> sameAttributes,
                                       String note) {
}
