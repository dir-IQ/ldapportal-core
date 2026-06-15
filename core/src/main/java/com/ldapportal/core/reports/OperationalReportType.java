// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

/**
 * Operational report types — directory metrics and integrity reports
 * every operator needs to manage the directory, independent of any
 * compliance program. Lives in core so the community edition can run
 * these without the GOVERNANCE entitlement.
 *
 * <p>Compliance-flavoured report types (access reviews, SoD, drift,
 * termination velocity, audit-log exports, privileged-account
 * inventory) live in {@code ee/governance} and are entitlement-
 * gated. See {@code docs/edition-boundary.md} for the split.</p>
 */
public enum OperationalReportType {
    USERS_IN_GROUP,
    USERS_IN_BRANCH,
    USERS_WITH_NO_GROUP,
    RECENTLY_ADDED,
    RECENTLY_MODIFIED,
    RECENTLY_DELETED,
    DISABLED_ACCOUNTS,
    MISSING_PROFILE_GROUPS,
}
