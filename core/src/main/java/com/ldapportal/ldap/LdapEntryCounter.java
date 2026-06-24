// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.FullLDAPInterface;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;

/**
 * Counts directory entries matching a filter <em>without</em> materialising
 * them.
 *
 * <p>Two things make this cheaper than {@code searchUsers(...).size()}:
 * <ul>
 *   <li>It requests the no-attributes OID ({@code 1.1}), so the server sends
 *       back bare DNs — nothing is mapped to an {@code LdapUser}/{@code LdapGroup}
 *       and the read-enricher chain (a per-page secUser join for ISVA linked
 *       mode) never runs.</li>
 *   <li>It prefers the server's paged-results <em>total estimate</em>
 *       (RFC 2696 §3.2): when the directory populates the size field of the
 *       first page's response control, that single round-trip is the answer.
 *       Active Directory and Oracle/OpenDJ return an estimate; OpenLDAP
 *       returns 0, in which case we fall back to paging through and counting
 *       DNs — still far cheaper than the mapped+enriched search.</li>
 * </ul>
 *
 * <p>The count is capped at {@code max}: a return value equal to {@code max}
 * means "at least {@code max}". A non-existent search base yields 0 rather
 * than an error, matching {@code LdapUserService.searchUsers}.</p>
 */
public final class LdapEntryCounter {

    private LdapEntryCounter() {}

    /**
     * @param conn     borrowed LDAP connection
     * @param baseDn   search base
     * @param filter   LDAP filter string
     * @param pageSize paging size for the fallback exact count
     * @param max      upper bound; counting stops once it's reached
     * @return number of matching entries, clamped to {@code max}
     */
    public static long count(FullLDAPInterface conn,
                             String baseDn,
                             String filter,
                             int pageSize,
                             long max) throws LDAPException {
        if (max <= 0) return 0L;
        int page = (int) Math.min(Math.max(1, pageSize), Math.max(1L, max));
        Filter parsed = Filter.create(filter);
        ASN1OctetString cookie = null;
        long total = 0;
        boolean firstPage = true;

        do {
            SearchRequest request = new SearchRequest(baseDn, SearchScope.SUB, parsed, "1.1");
            request.addControl(new SimplePagedResultsControl(page, cookie));

            SearchResult result;
            try {
                result = conn.search(request);
            } catch (LDAPSearchException e) {
                // Missing base DN isn't a failure for a count — there are
                // simply zero entries under it.
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return 0L;
                }
                throw e;
            }

            SimplePagedResultsControl response = SimplePagedResultsControl.get(result);

            // RFC 2696: the server MAY return its estimate of the total
            // result-set size on the first page. When it does (> 0), trust it
            // — that's the whole optimisation (one round-trip, no paging).
            if (firstPage && response != null && response.getSize() > 0) {
                return Math.min(response.getSize(), max);
            }
            firstPage = false;

            total += result.getEntryCount();
            if (total >= max) {
                return max;
            }

            cookie = (response != null && response.moreResultsToReturn())
                    ? response.getCookie()
                    : null;
        } while (cookie != null && cookie.getValue().length > 0);

        return total;
    }
}
