// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** Where a running directory diff has got to. */
public enum DirectoryDiffPhase {
    /** Reading the entries of side A. */
    READING_A,
    /** Reading the entries of side B. */
    READING_B,
    /** Pairing and comparing the entries read. */
    COMPARING,
    /** Finished, failed or cancelled. */
    DONE
}
