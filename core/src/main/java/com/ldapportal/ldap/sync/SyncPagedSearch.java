// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPInterface;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;

import java.util.ArrayList;
import java.util.List;

/**
 * Full enumeration of a search through the Simple Paged Results control (RFC
 * 2696), so a scope larger than the server's size limit is still read
 * <em>completely</em>. The reconciler's not-seen sweep and the content verifier
 * both depend on completeness: an unpaged search that trips the size limit
 * throws, which used to make every reconcile of a large scope a silent no-op.
 *
 * <p>The control is sent non-critical. A server that ignores it returns the
 * whole result in one response (or throws on its size limit, which the caller
 * reports as an incomplete scan exactly as before).
 */
public final class SyncPagedSearch {

    static final int DEFAULT_PAGE_SIZE = 500;

    private SyncPagedSearch() {
    }

    /**
     * @param pageSize entries per page; values below 1 fall back to {@link #DEFAULT_PAGE_SIZE}
     * @throws LDAPException when any page fails — the enumeration is then incomplete
     */
    public static List<SearchResultEntry> all(LDAPInterface conn, SearchRequest request, int pageSize)
            throws LDAPException {
        int size = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
        List<SearchResultEntry> out = new ArrayList<>();
        ASN1OctetString cookie = null;
        do {
            SearchRequest page = request.duplicate();
            page.setControls(new SimplePagedResultsControl(size, cookie, false));
            SearchResult result = conn.search(page);
            out.addAll(result.getSearchEntries());
            SimplePagedResultsControl response = SimplePagedResultsControl.get(result);
            cookie = (response != null && response.moreResultsToReturn()) ? response.getCookie() : null;
        } while (cookie != null && cookie.getValueLength() > 0);
        return out;
    }
}
