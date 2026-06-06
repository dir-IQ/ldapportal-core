// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * LDAP search scope used to bound a sync set's source enumeration. This is a
 * performance hint on <em>where</em> to enumerate, not the membership selector
 * (that is the applicability predicate, added in a later phase).
 */
public enum SyncScope {
    BASE,
    ONE,
    SUB
}
