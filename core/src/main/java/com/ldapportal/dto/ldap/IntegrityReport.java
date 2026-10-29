// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;

/**
 * Result of a referential integrity check.
 *
 * @param issues list of integrity issues found
 */
public record IntegrityReport(List<IntegrityIssue> issues) {

    /**
     * @param attribute for a {@code BROKEN_MEMBER}, the attribute holding the
     *                  reference ({@code member} or {@code uniqueMember}); else {@code null}
     * @param value     for a {@code BROKEN_MEMBER}, the referenced DN that doesn't
     *                  exist, as the group stores it; else {@code null}
     */
    public record IntegrityIssue(
            IssueType type,
            String dn,
            String description,
            String attribute,
            String value
    ) {
        public IntegrityIssue(IssueType type, String dn, String description) {
            this(type, dn, description, null, null);
        }
    }

    public enum IssueType {
        BROKEN_MEMBER,
        ORPHANED_ENTRY,
        EMPTY_GROUP
    }
}
