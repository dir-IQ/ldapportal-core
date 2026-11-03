// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.diff;

import com.ldapportal.entity.enums.DirectoryDiffCategory;

import java.util.List;

/**
 * One result row: an entry on one side, or a pair.
 *
 * @param seq        its position in the run (the key for {@code …/entries/{seq}})
 * @param attributes the attributes that differ (Different only)
 * @param note       why an entry that looks like it should pair didn't
 */
public record DirectoryDiffEntryResponse(int seq, DirectoryDiffCategory category, String dnA, String dnB,
                                         List<String> attributes, String note) {
}
