// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * An LDAP mutation type. Mirrors the four write operations on
 * {@code LDAPInterface}: add (entry creation), modify (attribute
 * modifications), delete (entry removal), modifyDN (rename / move).
 *
 * <p>Used by the changelog model to classify a parsed change record.
 * Search / compare / bind are not represented — they don't mutate state.
 */
public enum LdapChangeOp {
    ADD,
    MODIFY,
    DELETE,
    MODIFY_DN
}
