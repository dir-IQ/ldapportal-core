// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

import com.ldapportal.entity.enums.LdapChangeOp;

import java.util.Map;

/**
 * A changelog entry reconstructed into a replication-ready operation, before
 * DN / attribute mapping. Produced by {@link ChangelogStrategy#extractChange}
 * and consumed by the replication changelog poller (C3), which applies
 * {@code DnMapper} / {@code AttributeMapper} and enqueues a
 * {@code PendingReplicationEvent}.
 *
 * <p>{@code rawPayload} is in the <b>same shape</b> the
 * {@code ReplicationEnqueuer} builds from a captured live write, <em>before</em>
 * mapping — so the two capture paths converge on identical queue rows. Keys by
 * {@link #operation()}:
 * <ul>
 *   <li>{@code ADD}       — {@code attributes}: Map&lt;String,List&lt;String&gt;&gt;</li>
 *   <li>{@code MODIFY}    — {@code modifications}: List&lt;Map&gt; with
 *       {@code type} (ADD/REPLACE/DELETE), {@code name}, {@code values}</li>
 *   <li>{@code DELETE}    — empty</li>
 *   <li>{@code MODIFY_DN} — {@code newRdn}, {@code deleteOldRdn} (Boolean),
 *       {@code newSuperiorDn} (nullable)</li>
 * </ul>
 *
 * <p>See {@code docs/plans/2026-06-03-changelog-replication-design.md} §4.2.
 *
 * @param operation  the reconstructed write operation.
 * @param sourceDn   the changed entry's DN, in source-directory terms (pre-mapping).
 * @param rawPayload the pre-mapping payload in enqueuer shape (see above).
 */
public record ChangelogChange(
        LdapChangeOp operation,
        String sourceDn,
        Map<String, Object> rawPayload) {
}
