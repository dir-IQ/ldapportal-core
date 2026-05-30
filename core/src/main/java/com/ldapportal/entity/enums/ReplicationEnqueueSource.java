// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * Provenance of a replication event. Carried for diagnostics rather
 * than dispatch — the worker treats all events identically.
 *
 * <ul>
 *   <li>{@link #APP_INTERCEPT}     — captured by the
 *       ReplicatingLDAPInterface wrapper on a source-side
 *       {@code LdapConnectionFactory.withConnection} call. The v1
 *       enqueue source.</li>
 *   <li>{@link #SOURCE_CHANGELOG}  — future path: polled from the
 *       source directory's external changelog
 *       ({@code cn=changelog} on OUD/OpenDJ/389DS,
 *       {@code cn=accesslog} on OpenLDAP, DirSync on AD). Adds
 *       coverage for out-of-band writes that the app intercept
 *       can't see. Not implemented in v1 — listed here so the
 *       schema doesn't need a migration when it lands.</li>
 * </ul>
 */
public enum ReplicationEnqueueSource {
    APP_INTERCEPT,
    SOURCE_CHANGELOG
}
