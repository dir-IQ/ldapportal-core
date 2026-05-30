// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * LDAP mutation captured for replication. Mirrors the four write
 * operations on {@code LDAPInterface}: add (entry creation), modify
 * (attribute modifications), delete (entry removal), modifyDN
 * (rename / move).
 *
 * <p>Search / compare / bind are explicitly NOT captured — they don't
 * mutate state and are uninteresting for replication.
 */
public enum ReplicationOperationType {
    ADD,
    MODIFY,
    DELETE,
    MODIFY_DN
}
