// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

/**
 * A single dry-run preview row for a bulk delete. Shows the DN that each CSV
 * row resolved to and what would happen on commit — no LDAP writes have
 * occurred. The frontend renders the {@link Disposition} as a colour-coded
 * badge so an admin sees exactly what will (and won't) be deleted before
 * confirming.
 */
public record BulkDeletePreviewRow(
        int rowNumber,
        /** Resolved target DN, or {@code null} when the row couldn't be resolved (NOT_FOUND / AMBIGUOUS / INVALID). */
        String dn,
        Disposition disposition,
        /** Human-readable explanation for non-deletable dispositions; {@code null} for WILL_DELETE. */
        String note) {

    public enum Disposition {
        /** Resolved to exactly one in-scope, existing entry — will be deleted on commit. */
        WILL_DELETE,
        /** No entry exists at the DN / matching the key — skipped on commit. */
        NOT_FOUND,
        /** Resolved to a DN outside the caller's authorized scope — refused on commit. */
        OUT_OF_SCOPE,
        /** Key-attribute value matched more than one entry — refused (never guesses). */
        AMBIGUOUS,
        /** Row had no usable value in the configured column. */
        INVALID
    }
}
