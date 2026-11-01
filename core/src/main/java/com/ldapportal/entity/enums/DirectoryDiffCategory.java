// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** Which group a directory diff puts an entry (or a pair of entries) in. */
public enum DirectoryDiffCategory {
    /** On both sides, every compared attribute equal. */
    SAME,
    /** On both sides, at least one compared attribute different. */
    DIFFERENT,
    /** On side A with no matching entry on side B. */
    ONLY_A,
    /** On side B with no matching entry on side A. */
    ONLY_B
}
