// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.schema;

/**
 * What applying a previewed schema element would do to the live directory
 * schema.
 */
public enum SchemaElementAction {
    /** The element's name/OID is not in the live schema — a new definition. */
    ADD_NEW,
    /** The element already exists and this record modifies it (OpenDJ only). */
    MODIFY_EXISTING,
    /**
     * The change cannot be applied online for this vendor (e.g. modifying or
     * deleting an existing element on OpenLDAP, or a record targeting a DN
     * outside the schema container). Always blocking.
     */
    UNSUPPORTED
}
