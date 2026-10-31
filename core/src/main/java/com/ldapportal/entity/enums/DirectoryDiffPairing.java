// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** How a directory diff decides which entry on side B goes with one on side A. */
public enum DirectoryDiffPairing {
    /** The same place below each side's base DN (A's base swapped for B's). */
    DN,
    /** The same value of a chosen attribute (e.g. employeeNumber). */
    ATTRIBUTE
}
