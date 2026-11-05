// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import java.util.List;

/**
 * What an import did, or with {@code dryRun} would do.
 *
 * @param profile   the created or updated profile; {@code null} on a dry run
 * @param dnChanges DN values the base-DN rewrite changes, for review
 * @param warnings  things to check before enabling the profile (e.g. no approvers)
 */
public record ProfileImportResult(
        ProfileResponse profile,
        List<DnChange> dnChanges,
        List<String> warnings) {

    /** One rewritten DN value: which setting, and its value before and after. */
    public record DnChange(String field, String from, String to) {
    }
}
