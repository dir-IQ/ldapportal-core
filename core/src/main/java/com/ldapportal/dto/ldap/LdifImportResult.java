// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;

/**
 * Result of an LDIF import operation.
 *
 * @param added   number of entries successfully added
 * @param updated number of entries updated (conflict = OVERWRITE)
 * @param skipped number of entries skipped (conflict = SKIP, or dry-run)
 * @param failed  number of entries that failed
 * @param errors  per-entry error details
 */
public record LdifImportResult(
        int added,
        int updated,
        int skipped,
        int failed,
        List<LdifImportError> errors
) {
    public record LdifImportError(String dn, String message) {}
}
