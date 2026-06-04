// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

/**
 * Neutral read context for a changelog search, decoupling
 * {@link ChangelogStrategy} from any particular source-config entity.
 *
 * <p>Both drivers build one of these:
 * <ul>
 *   <li>the audit reader ({@code LdapChangelogReader}) from an
 *       {@code AuditDataSource}, passing {@code afterChangeNumber = null}
 *       (it dedups via the {@code audit_events} table, not a cursor);</li>
 *   <li>the replication poller (C3) from a {@code ReplicationLinkSnapshot},
 *       passing the link's cursor as {@code afterChangeNumber} and the link's
 *       {@code sourceBaseDn} as {@code branchFilterDn}.</li>
 * </ul>
 *
 * <p>See {@code docs/plans/2026-06-03-changelog-replication-design.md} §4.1.
 *
 * @param changelogBaseDn  base DN of the changelog container (e.g. {@code cn=changelog}).
 * @param branchFilterDn   restrict to entries under this DN; {@code null} = no branch restriction.
 * @param afterChangeNumber exclusive lower bound on {@code changeNumber}; {@code null} = no lower bound.
 */
public record ChangelogReadContext(
        String changelogBaseDn,
        String branchFilterDn,
        Long afterChangeNumber) {
}
