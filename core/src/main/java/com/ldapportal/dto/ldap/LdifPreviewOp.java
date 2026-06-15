// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

/**
 * What an LDIF record would do when applied, as classified by the preview.
 * {@code ERROR} marks a record that could not be parsed or is unusable.
 */
public enum LdifPreviewOp {
    ADD,
    MODIFY,
    DELETE,
    MODDN,
    SKIP,
    ERROR
}
