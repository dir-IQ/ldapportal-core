// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

/**
 * A single per-row finding surfaced by the LDIF preview.
 *
 * @param severity {@code ERROR} / {@code WARNING} / {@code INFO}
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

    public static LdifPreviewIssue outOfScope(String baseDn) {
        return new LdifPreviewIssue(WARNING, "OUT_OF_SCOPE", "DN is not under the directory base " + baseDn);
    }

    public static LdifPreviewIssue conflictExists() {
        return new LdifPreviewIssue(INFO, "CONFLICT_EXISTS", "An entry with this DN already exists in the directory");
    }
}
