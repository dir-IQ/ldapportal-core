// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

/**
 * A single per-row finding surfaced by the LDIF preview.
 *
 * @param severity {@code ERROR} / {@code WARNING} / {@code INFO}. {@code ERROR}
 *                 is blocking: the preview's apply step never sends such a row
 *                 to the server.
 * @param code     stable machine code: {@code PARSE_ERROR}, {@code INVALID_DN},
 *                 {@code OUT_OF_SCOPE}, {@code CONFLICT_EXISTS}
 * @param message  human-readable detail
 */
public record LdifPreviewIssue(String severity, String code, String message) {

    public static final String ERROR = "ERROR";
    public static final String WARNING = "WARNING";
    public static final String INFO = "INFO";

    public static LdifPreviewIssue parseError(String message) {
        return new LdifPreviewIssue(ERROR, "PARSE_ERROR", message);
    }

    public static LdifPreviewIssue invalidDn(String dn) {
        return new LdifPreviewIssue(ERROR, "INVALID_DN", "Not a valid DN: " + dn);
    }

    /**
     * A DN that parses but falls outside the directory's configured base DN.
     * Blocking ({@code ERROR}): the importer sends the DN verbatim — it is never
     * re-based under the directory root — so a server rooted at {@code baseDn}
     * rejects the write (typically {@code NO_SUCH_OBJECT} on the missing parent).
     * Surfacing it as an error keeps the preview from counting adds that can't
     * land, and the apply step skips these rows.
     */
    public static LdifPreviewIssue outOfScope(String baseDn) {
        return new LdifPreviewIssue(ERROR, "OUT_OF_SCOPE",
                "DN is outside the directory base " + baseDn + " — the server will reject it");
    }

    public static LdifPreviewIssue conflictExists() {
        return new LdifPreviewIssue(INFO, "CONFLICT_EXISTS", "An entry with this DN already exists in the directory");
    }
}
