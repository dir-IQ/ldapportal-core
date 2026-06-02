// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Reads a directory subtree for reconciliation. Uses
 * {@link LdapConnectionFactory#withConnectionUnreplicated} so the read
 * never passes through the replication-capture wrapper.
 *
 * <p>A subtree read that the server truncates (size-limit) would make
 * reconciliation act on a partial view — potentially mass-deleting
 * "extra" target entries that were merely beyond the page. The search
 * therefore propagates any LDAP failure (including
 * {@code SIZE_LIMIT_EXCEEDED}) to the caller, which fails the run rather
 * than diffing truncated data.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationReadOps {

    private final LdapConnectionFactory connectionFactory;

    /**
     * Stream every entry at or under {@code baseDn} to {@code consumer}, a
     * page at a time via {@link SimplePagedResultsControl} (R-PP1). The page
     * loop runs inside a single {@code withConnectionUnreplicated} lambda so
     * every paged search reuses the <em>same</em> connection — paged-results
     * cookie continuity requires it. Entries are released as each page is
     * consumed, so the full result set is never held at once. Any LDAP error
     * (including a server size-limit) propagates so the run fails rather than
     * acting on a truncated view.
     */
    public void streamSubtree(DirectoryConnection dc, String baseDn, int pageSize,
                              Consumer<ReconEntry> consumer) {
        connectionFactory.withConnectionUnreplicated(dc, iface -> {
            ASN1OctetString cookie = null;
            do {
                SearchRequest req = new SearchRequest(
                        baseDn, SearchScope.SUB,
                        Filter.createPresenceFilter("objectClass"),
                        "*");
                req.setControls(new SimplePagedResultsControl(pageSize, cookie));
                SearchResult result = iface.search(req);
                for (SearchResultEntry e : result.getSearchEntries()) {
                    consumer.accept(toReconEntry(e));
                }
                SimplePagedResultsControl resp = SimplePagedResultsControl.get(result);
                cookie = (resp != null && resp.getCookie().getValueLength() > 0)
                        ? resp.getCookie() : null;
            } while (cookie != null);
            return null;
        });
    }

    /**
     * Read a single entry by DN (BASE scope), or empty if it no longer
     * exists. Used by the checksum path's pass 2 to hydrate only the
     * discrepant entries.
     */
    public Optional<ReconEntry> readEntry(DirectoryConnection dc, String dn) {
        return connectionFactory.withConnectionUnreplicated(dc, iface -> {
            try {
                SearchResultEntry e = iface.searchForEntry(new SearchRequest(
                        dn, SearchScope.BASE,
                        Filter.createPresenceFilter("objectClass"),
                        "*"));
                return e == null ? Optional.<ReconEntry>empty() : Optional.of(toReconEntry(e));
            } catch (LDAPException ex) {
                if (ex.getResultCode() == ResultCode.NO_SUCH_OBJECT) return Optional.<ReconEntry>empty();
                throw ex;
            }
        });
    }

    private static ReconEntry toReconEntry(SearchResultEntry e) {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        for (Attribute a : e.getAttributes()) {
            attrs.put(a.getName(), Arrays.asList(a.getValues()));
        }
        return new ReconEntry(e.getDN(), attrs);
    }
}
