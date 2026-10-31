// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.diff;

import java.util.List;

/**
 * One attribute that differs between the two entries of a pair: its values
 * only on A, only on B, and on both. Each list holds at most 100 values; its
 * count says how many there were.
 */
public record DirectoryDiffAttributeDiff(String name, List<String> onlyA, int onlyACount, List<String> onlyB,
                                         int onlyBCount, List<String> both, int bothCount) {
}
