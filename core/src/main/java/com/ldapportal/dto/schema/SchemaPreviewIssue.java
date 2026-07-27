// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.schema;

/**
 * A single problem found while previewing a schema-LDIF element, mirroring the
 * severity/code/message shape of {@code LdifPreviewIssue}.
 *
 * <p>{@link #ERROR} issues block the whole apply; {@link #WARNING} issues are
 * advisory and do not.</p>
 */
public record SchemaPreviewIssue(String severity, String code, String message) {

    public static final String WARNING = "WARNING";
    public static final String ERROR = "ERROR";

    public static SchemaPreviewIssue parseError(String message) {
        return new SchemaPreviewIssue(ERROR, "PARSE_ERROR", message);
    }

    public static SchemaPreviewIssue outOfScope(String container) {
        return new SchemaPreviewIssue(ERROR, "OUT_OF_SCOPE",
                "Record targets a DN outside the schema container (" + container + ").");
    }

    public static SchemaPreviewIssue oidCollision(String oid, String existingName) {
        return new SchemaPreviewIssue(ERROR, "OID_COLLISION",
                "OID " + oid + " is already used by '" + existingName + "' in the live schema.");
    }

    public static SchemaPreviewIssue modifyUnsupported(String container) {
        return new SchemaPreviewIssue(ERROR, "MODIFY_UNSUPPORTED",
                "This directory does not support modifying or removing existing schema "
                        + "elements online (" + container + "); only adding new ones.");
    }

    public static SchemaPreviewIssue deleteUnsupported() {
        return new SchemaPreviewIssue(ERROR, "DELETE_UNSUPPORTED",
                "Deleting an entry from the schema container is not supported.");
    }

    public static SchemaPreviewIssue modifiesExisting() {
        return new SchemaPreviewIssue(WARNING, "MODIFIES_EXISTING",
                "This element already exists; applying will modify the live definition.");
    }
}
