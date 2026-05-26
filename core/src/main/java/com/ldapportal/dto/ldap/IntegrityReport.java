// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;

/**
 * Result of a referential integrity check.
 *
 * @param issues list of integrity issues found
 */
public record IntegrityReport(List<IntegrityIssue> issues) {

    public record IntegrityIssue(
            IssueType type,
            String dn,
            String description
    ) {}

    public enum IssueType {
        BROKEN_MEMBER,
        ORPHANED_ENTRY,
        EMPTY_GROUP
    }
}
