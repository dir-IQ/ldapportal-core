// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.SyncScope;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * In-stream referential closure: when an identity's projection changes, find the
 * source entries that reference it (via the declared reference attributes) and
 * enqueue their recompute, so a group's membership / a manager pointer / an ACL
 * subject re-projects without waiting for the next reconcile.
 *
 * <p>Termination is hash-gated by the engine: the engine only invokes
 * {@link #fanOut} when a transition actually changed the target, so a referrer
 * whose recompute yields an unchanged content hash emits no further closure and
 * the cascade stops once projected outputs stabilize.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClosureResolver {

    private final SyncSetRepository syncSetRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final LdapConnectionFactory connectionFactory;
    private final RecomputeEnqueuer enqueuer;

    public void fanOut(SyncLink link, String changedSourceDn) {
        DirectoryConnection source = directoryRepo.findById(link.getSourceDirId()).orElse(null);
        if (source == null) {
            return;
        }
        String changedNorm = SyncDnUtil.normalize(changedSourceDn);
        for (SyncSet set : syncSetRepo.findAllByLinkId(link.getId())) {
            if (!set.isEnabled()) {
                continue;
            }
            Filter filter = referrerFilter(SyncReferenceAttributes.forSet(set), changedSourceDn);
            if (filter == null) {
                continue;
            }
            String base = set.getObjectScopeBaseDn() != null ? set.getObjectScopeBaseDn() : source.getBaseDn();
            SearchScope scope = scopeOf(set);
            try {
                List<String> referrers = connectionFactory.withConnectionUnreplicated(source, conn -> {
                    // "1.1" => return DNs only, no attributes.
                    SearchRequest req = new SearchRequest(base, scope, filter, "1.1");
                    List<String> dns = new ArrayList<>();
                    for (SearchResultEntry e : conn.search(req).getSearchEntries()) {
                        dns.add(e.getDN());
                    }
                    return dns;
                });
                for (String dn : referrers) {
                    if (SyncDnUtil.normalize(dn).equalsIgnoreCase(changedNorm)) {
                        continue; // don't re-enqueue the entry that just changed
                    }
                    enqueuer.enqueue(set.getId(), dn, null);
                }
            } catch (Exception ex) {
                log.warn("Closure search failed for sync set {} on change to {}: {}",
                        set.getId(), changedSourceDn, ex.toString());
            }
        }
    }

    private static Filter referrerFilter(List<String> referenceAttributes, String changedDn) {
        if (referenceAttributes.isEmpty()) {
            return null;
        }
        List<Filter> ors = new ArrayList<>(referenceAttributes.size());
        for (String attr : referenceAttributes) {
            ors.add(Filter.createEqualityFilter(attr, changedDn));
        }
        return ors.size() == 1 ? ors.get(0) : Filter.createORFilter(ors);
    }

    private static SearchScope scopeOf(SyncSet set) {
        SyncScope s = set.getObjectScope() == null ? SyncScope.SUB : set.getObjectScope();
        return switch (s) {
            case BASE -> SearchScope.BASE;
            case ONE -> SearchScope.ONE;
            case SUB -> SearchScope.SUB;
        };
    }
}
