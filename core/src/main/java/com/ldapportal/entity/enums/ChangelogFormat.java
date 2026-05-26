// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * Identifies the changelog / audit-log format exposed by an LDAP server.
 */
public enum ChangelogFormat {

    /** Oracle DSEE / UnboundID — {@code cn=changelog} with {@code changeNumber}. */
    DSEE_CHANGELOG,

    /** OpenLDAP {@code slapo-accesslog} overlay — {@code cn=accesslog} with {@code reqStart}. */
    OPENLDAP_ACCESSLOG,

    /** Active Directory DirSync control — polls incremental changes via OID 1.2.840.113556.1.4.417. */
    AD_DIRSYNC
}
