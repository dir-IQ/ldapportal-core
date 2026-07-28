// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.schema;

import java.util.List;

/**
 * One schema element (attributeType or objectClass) found in an uploaded LDIF,
 * classified against the live directory schema.
 *
 * @param rowNumber  1-based row of the source LDIF record
 * @param kind       attributeType vs objectClass
 * @param name       the element's NAME (or OID when unnamed); null if unparseable
 * @param oid        the element's OID; null if unparseable
 * @param action     what applying it would do
 * @param targetDn   the DN of the LDIF record carrying it
 * @param definition the raw (normalized) schema definition string
 * @param issues     problems found; any ERROR-severity issue is blocking
 */
public record SchemaPreviewElement(
        int rowNumber,
        SchemaElementKind kind,
        String name,
        String oid,
        SchemaElementAction action,
        String targetDn,
        String definition,
        List<SchemaPreviewIssue> issues) {

    /** True when this element must not be applied (an error issue or UNSUPPORTED). */
    public boolean blocking() {
        return action == SchemaElementAction.UNSUPPORTED
                || issues.stream().anyMatch(i -> SchemaPreviewIssue.ERROR.equals(i.severity()));
    }
}
