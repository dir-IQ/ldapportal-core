// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.schema;

import java.util.List;

/**
 * Outcome of applying a previewed schema LDIF. LDAP schema changes are not
 * transactional, so records are applied one at a time and per-record failures
 * are reported rather than aborting the batch.
 *
 * @param applied number of records applied successfully
 * @param failed  number of records that failed
 * @param errors  per-record failure detail
 */
public record SchemaUpdateResult(
        int applied,
        int failed,
        List<SchemaUpdateError> errors) {

    public record SchemaUpdateError(String targetDn, String message) {}
}
