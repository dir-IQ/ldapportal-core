// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-stream referential closure: when an identity's projection changes, find the
 * source entries that reference it (via the declared reference attributes) and
 * enqueue their recompute, so a group's membership / a manager pointer / an ACL
 * subject re-projects without waiting for the next reconcile.
 *
 * <p>Referrers are searched in the source of <em>every</em> enabled link, not only
 * the link whose entry changed, because {@link MembershipReferenceResolvers}
 * resolves references across links (an IVIA {@code secDN} under {@code c=admin}
 * points at an entry mirrored by the {@code c=us} link). The changed link's own
 * sets are always searched, even when the link is disabled, to match the engine
 * processing it.
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
    private final SyncLinkRepository syncLinkRepo;
    private final DirectoryConnectionRepository directoryRepo;
    private final LdapConnectionFactory connectionFactory;
    private final RecomputeEnqueuer enqueuer;

    public void fanOut(SyncLink link, String changedSourceDn) {
        String changedNorm = SyncDnUtil.normalize(changedSourceDn);
        Map<UUID, Optional<SyncLink>> links = new HashMap<>();
        links.put(link.getId(), Optional.of(link));
        Map<UUID, Optional<DirectoryConnection>> sources = new HashMap<>();
        for (SyncSet set : syncSetRepo.findAllByEnabledTrue()) {
            boolean own = set.getLinkId().equals(link.getId());
            SyncLink setLink = links.computeIfAbsent(set.getLinkId(), syncLinkRepo::findById).orElse(null);
            if (setLink == null || (!own && !setLink.isEnabled())) {
                continue;
            }
            DirectoryConnection source = sources
                    .computeIfAbsent(setLink.getSourceDirId(), directoryRepo::findById).orElse(null);
            if (source == null) {
                continue;
            }
            fanOutSet(set, source, changedSourceDn, changedNorm);
        }
    }

    private void fanOutSet(SyncSet set, DirectoryConnection source, String changedSourceDn, String changedNorm) {
        Filter filter = referrerFilter(SyncReferenceAttributes.forSet(set), changedSourceDn);
        if (filter == null) {
            return;
        }
        String base = set.getObjectScopeBaseDn() != null ? set.getObjectScopeBaseDn() : source.getBaseDn();
        SearchScope scope = SyncScopes.searchScope(set);
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
}
