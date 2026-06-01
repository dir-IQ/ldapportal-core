// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * All entries at or under {@code baseDn}, with user attributes.
     * Operational attributes are not requested (the differ excludes them
     * anyway). Throws (via the factory) on any LDAP error.
     */
    public List<ReconEntry> readSubtree(DirectoryConnection dc, String baseDn) {
        return connectionFactory.withConnectionUnreplicated(dc, iface -> {
            SearchRequest req = new SearchRequest(
                    baseDn, SearchScope.SUB,
                    Filter.createPresenceFilter("objectClass"),
                    "*");
            SearchResult result = iface.search(req);
            List<ReconEntry> entries = new ArrayList<>(result.getEntryCount());
            for (SearchResultEntry e : result.getSearchEntries()) {
                Map<String, List<String>> attrs = new LinkedHashMap<>();
                for (Attribute a : e.getAttributes()) {
                    attrs.put(a.getName(), Arrays.asList(a.getValues()));
                }
                entries.add(new ReconEntry(e.getDN(), attrs));
            }
            return entries;
        });
    }
}
