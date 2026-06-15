// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

/**
 * Parameters for a bulk user delete operation driven by a CSV upload.
 *
 * <p>Each CSV data row identifies one user to delete. Two resolution modes:</p>
 * <ul>
 *   <li><b>DN mode</b> — {@code keyAttribute} is {@code null}/blank or {@code "dn"}.
 *       The value read from {@code valueColumn} (default header {@code "dn"}) is
 *       treated as a full distinguished name. This is the natural round-trip from
 *       a CSV <em>export</em>, which always emits a {@code dn} column.</li>
 *   <li><b>Key-attribute mode</b> — {@code keyAttribute} names an LDAP attribute
 *       (e.g. {@code "uid"}). Each value is resolved to a DN via an equality search
 *       under {@code baseDn} (required in this mode). Zero matches → row reported as
 *       NOT_FOUND; more than one → AMBIGUOUS; both are reported, never guessed.</li>
 * </ul>
 *
 * <p>Unlike create/move there is <b>no approval workflow</b> on delete — the
 * destructive operation is gated by the {@code bulk.delete} feature plus the
 * mandatory dry-run preview, typed confirmation, and per-request row cap.</p>
 */
public record BulkDeleteRequest(
        /** {@code null}/blank or {@code "dn"} = DN mode; otherwise the attribute to resolve (e.g. {@code uid}). */
        String keyAttribute,
        /** CSV header holding the value to read. Defaults to {@code keyAttribute}, or {@code "dn"} in DN mode. */
        String valueColumn,
        /** Search base for key-attribute mode. Required (and scope-checked) when {@code keyAttribute} is set. */
        String baseDn,
        /** Whether to treat the first CSV row as headers (true, default) or data (false). */
        Boolean skipHeaderRow) {
}
