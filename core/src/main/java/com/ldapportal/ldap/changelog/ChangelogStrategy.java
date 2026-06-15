// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Abstraction over different LDAP changelog / audit-log formats.
 *
 * <p>Each implementation knows how to build the search request and extract
 * audit-relevant fields from entries returned by a specific format. The same
 * strategies drive both the audit reader ({@code LdapChangelogReader}) and the
 * replication changelog poller (C3) — the audit path passes
 * {@code afterChangeNumber = null} and dedups via {@code audit_events}, while
 * the poller passes a cursor and dedups via {@code replication_events}.</p>
 */
public interface ChangelogStrategy {

    /** Build the LDAP search request for this changelog format. */
    SearchRequest buildSearchRequest(ChangelogReadContext ctx, int sizeLimit) throws LDAPException;

    /** Extract a unique entry identifier ({@code changeNumber} or {@code reqStart}). {@code null} → skip. */
    String extractEntryId(SearchResultEntry entry);

    /** Extract the target DN of the changed entry. */
    String extractTargetDn(SearchResultEntry entry);

    /** Build the detail map for the {@link com.ldapportal.entity.AuditEvent}. */
    Map<String, Object> extractDetail(SearchResultEntry entry);

    /** Extract the timestamp when the operation occurred. */
    OffsetDateTime extractOccurredAt(SearchResultEntry entry);

    /** Whether this entry represents a recordable write operation. */
    boolean isRecordable(SearchResultEntry entry);

    /**
     * Reconstruct the entry into a replication-ready operation, or empty if
     * this format/entry cannot be turned into a structured write. Default empty:
     * strategies that only support audit detection (not replication) keep it
     * until their parsers land, so the poller's validation refuses to enable
     * {@code CHANGELOG} capture for them.
     *
     * @throws ChangelogParseException if the entry's change content is malformed
     *         (distinct from an empty result, which is a normal skip).
     */
    default Optional<ChangelogChange> extractChange(SearchResultEntry entry) {
        return Optional.empty();
    }
}
