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
    AD_DIRSYNC,

    /**
     * Oracle Unified Directory / OpenDJ external changelog in <em>cookie mode</em>
     * — {@code cn=changelog} read with the External Changelog Request Control
     * (OID 1.3.6.1.4.1.26027.1.5.4). The cursor is the replication-CSN-based
     * {@code changeLogCookie}, valid on every replica in the topology, so a link
     * survives failover / load-balanced source nodes (the integer
     * {@code changeNumber} of {@link #DSEE_CHANGELOG} is assigned per server).
     * Directory-sync capture only; not an audit-source format.
     */
    OUD_COOKIE_CHANGELOG
}
