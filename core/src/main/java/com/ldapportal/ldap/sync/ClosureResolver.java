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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /** Changed DNs per batched referrer search in {@link #fanOutAll}. */
    static final int DN_BATCH = 50;

    public void fanOut(SyncLink link, String changedSourceDn) {
        String changedNorm = SyncDnUtil.normalize(changedSourceDn);
        forEachSet(link, (set, source) -> fanOutSet(set, source, changedSourceDn, changedNorm));
    }

    /**
     * Fan out once for a reconcile's changes: {@code changed} maps each source
     * DN whose target the run changed to the position of that recompute in the
     * run, and {@code visited} maps each source DN the run recomputed (in set
     * {@code runSetId}) to its position. Referrers are found with batched
     * searches of {@link #DN_BATCH} changed DNs at a time. A referrer in
     * {@code runSetId} is enqueued only when it was recomputed before a change
     * it refers to, or not at all; a later recompute already saw the change.
     * Referrers in other sets are always enqueued. {@code beforeSearch} is
     * given the first DN of each batch before it's searched (the reconcile's
     * heartbeat and progress).
     */
    public void fanOutAll(SyncLink link, UUID runSetId, Map<String, Integer> changed, Map<String, Integer> visited,
                          java.util.function.Consumer<String> beforeSearch) {
        if (changed.isEmpty()) {
            return;
        }
        List<String> dns = new ArrayList<>(changed.keySet());
        forEachSet(link, (set, source) -> {
            List<String> refAttrs = SyncReferenceAttributes.forSet(set);
            if (refAttrs.isEmpty()) {
                return;
            }
            boolean own = set.getId().equals(runSetId);
            String base = set.getObjectScopeBaseDn() != null ? set.getObjectScopeBaseDn() : source.getBaseDn();
            SearchScope scope = SyncScopes.searchScope(set);
            Set<String> enqueued = new HashSet<>();
            for (int from = 0; from < dns.size(); from += DN_BATCH) {
                List<String> chunk = dns.subList(from, Math.min(dns.size(), from + DN_BATCH));
                beforeSearch.accept(chunk.get(0));
                List<Filter> ors = new ArrayList<>(refAttrs.size() * chunk.size());
                for (String attr : refAttrs) {
                    for (String dn : chunk) {
                        ors.add(Filter.createEqualityFilter(attr, dn));
                    }
                }
                try {
                    List<SearchResultEntry> referrers = connectionFactory.withConnectionUnreplicated(source, conn ->
                            SyncPagedSearch.all(conn, new SearchRequest(base, scope, Filter.createORFilter(ors),
                                    refAttrs.toArray(new String[0])), source.getPagingSize()));
                    for (SearchResultEntry e : referrers) {
                        String referrer = SyncDnUtil.normalize(e.getDN());
                        if (!enqueued.contains(referrer)
                                && needsRecompute(e, referrer, refAttrs, changed, own ? visited.get(referrer) : null,
                                        own)) {
                            enqueued.add(referrer);
                            enqueuer.enqueue(set.getId(), e.getDN(), null);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("Closure search failed for sync set {} on {} changes: {}",
                            set.getId(), chunk.size(), ex.toString());
                }
            }
        });
    }

    /**
     * Whether {@code e} refers to a changed DN other than itself, and (in the
     * run's own set) wasn't recomputed after that change. The search matched
     * {@code e}, so when none of the values it returned names a changed DN they
     * are incomplete (AD returns a large {@code member} in ranges, as
     * {@code member;range=0-1499}) and it's enqueued to be safe.
     */
    static boolean needsRecompute(SearchResultEntry e, String referrer, List<String> refAttrs,
                                  Map<String, Integer> changed, Integer visitedAt, boolean own) {
        boolean sawChange = false;
        boolean sawSelf = false;
        for (String attr : refAttrs) {
            for (com.unboundid.ldap.sdk.Attribute a : e.getAttributesWithOptions(attr, null)) {
                for (String value : a.getValues()) {
                    String ref = SyncDnUtil.normalize(value);
                    if (ref.equalsIgnoreCase(referrer)) {
                        sawSelf = true; // don't re-enqueue the entry that just changed
                        continue;
                    }
                    Integer changedAt = changed.get(ref);
                    if (changedAt != null) {
                        sawChange = true;
                        if (!own || visitedAt == null || visitedAt < changedAt) {
                            return true;
                        }
                    }
                }
            }
        }
        return !sawChange && !sawSelf;
    }

    /** Every enabled set whose source could hold referrers, with that source (see the class doc). */
    private void forEachSet(SyncLink link, java.util.function.BiConsumer<SyncSet, DirectoryConnection> each) {
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
            each.accept(set, source);
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
