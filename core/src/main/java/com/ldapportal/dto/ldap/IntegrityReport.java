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
     * @param attribute for a {@code BROKEN_MEMBER} or {@code BROKEN_REFERENCE}, the
     *                  attribute holding the reference ({@code member},
     *                  {@code uniqueMember}, or e.g. {@code secDN}); else {@code null}
     * @param value     for those types, the referenced DN that doesn't exist, as the
     *                  entry stores it ({@code null} for a required reference that is
     *                  missing altogether); else {@code null}
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
        /**
         * Another DN-valued attribute names a DN no entry has, e.g. an IVIA
         * account's {@code secDN}. Checked by addon-contributed
         * {@link com.ldapportal.core.integrity.IntegrityCheckContributor}s.
         */
        BROKEN_REFERENCE,
        ORPHANED_ENTRY,
        EMPTY_GROUP
    }
}
