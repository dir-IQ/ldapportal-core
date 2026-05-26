// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

import com.ldapportal.entity.AuditDataSource;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Abstraction over different LDAP changelog / audit-log formats.
 *
 * <p>Each implementation knows how to build the search request and extract
 * audit-relevant fields from entries returned by a specific format.</p>
 */
public interface ChangelogStrategy {

    /** Build the LDAP search request for this changelog format. */
    SearchRequest buildSearchRequest(AuditDataSource src, int sizeLimit) throws LDAPException;

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
}
