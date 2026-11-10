// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;
import java.util.Map;

/**
 * A page of user search results plus what lies beyond it: the Users page's
 * truncation notice. Mirrors the superadmin Directory Search page's
 * {@code LdapBrowseService.SearchPage}.
 *
 * @param entries           the entries returned (at most the requested limit)
 * @param truncated         more entries matched than were returned
 * @param total             how many entries matched in all; exact unless
 *                          {@code totalIsLowerBound}
 * @param totalIsLowerBound the count stopped at the server-side ceiling, so
 *                          at least {@code total} entries matched
 * @param groups            when asked for: each returned entry's DN → the DNs of
 *                          the groups it belongs to, directly or through
 *                          nesting, among the groups the caller can see (the
 *                          Groups column; built like the user export's
 *                          {@code allgroups}). {@code null} when not asked for.
 */
public record UserSearchPage(List<LdapEntryResponse> entries,
                             boolean truncated,
                             int total,
                             boolean totalIsLowerBound,
                             Map<String, List<String>> groups) {

    public UserSearchPage(List<LdapEntryResponse> entries, boolean truncated,
                          int total, boolean totalIsLowerBound) {
        this(entries, truncated, total, totalIsLowerBound, null);
    }

    /** This page with {@code groups} attached. */
    public UserSearchPage withGroups(Map<String, List<String>> groups) {
        return new UserSearchPage(entries, truncated, total, totalIsLowerBound, groups);
    }
}
