// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;

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
 */
public record UserSearchPage(List<LdapEntryResponse> entries,
                             boolean truncated,
                             int total,
                             boolean totalIsLowerBound) {}
