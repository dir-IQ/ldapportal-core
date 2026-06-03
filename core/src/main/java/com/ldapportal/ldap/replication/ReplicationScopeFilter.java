// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The single authority for a replication link's <b>exclude filter</b> (§7B):
 * an entry within the replicated DIT that MATCHES the link's RFC 4515
 * {@code excludeFilter} is excluded from replication entirely — never created,
 * modified, or deleted on the target; a pre-existing target copy is left
 * untouched ("excluded ⇒ invisible", §7B.1).
 *
 * <p><b>Cardinal rule:</b> exclusion is evaluated <em>here only</em> and applied
 * identically by all three paths — live capture, changelog capture, and
 * reconciliation. If they disagreed, one would add an entry the other proposes
 * to delete (perpetual flapping).
 *
 * <p>Evaluation is purely in-memory ({@link Filter#matchesEntry}); the parsed
 * filter is cached per filter string (parsed once, not per entry). The filter is
 * written in <b>source</b> attribute terms and only ever matched against
 * <b>source</b> entries, so attribute renaming ({@link AttributeMapper}) can't
 * confuse it.
 *
 * <p><b>Fail-open:</b> an unparseable filter (rejected at config time, so this is
 * defensive) or an offline-unevaluable component (server-side-only extensible
 * matching) yields "not excluded" — replication should never silently drop an
 * entry because the filter couldn't be evaluated. Static helper, mirroring
 * {@link DnMapper} / {@link AttributeMapper}.
 */
@Slf4j
public final class ReplicationScopeFilter {

    private ReplicationScopeFilter() {
    }

    /** Parsed filters keyed by filter string (bounded — one per distinct link filter). */
    private static final ConcurrentMap<String, Optional<Filter>> CACHE = new ConcurrentHashMap<>();

    public static boolean hasExcludeFilter(ReplicationLinkSnapshot link) {
        return link.excludeFilter() != null && !link.excludeFilter().isBlank();
    }

    /** Whether {@code sourceEntry} is excluded from replication by the link's filter. */
    public static boolean isExcluded(ReplicationLinkSnapshot link, Entry sourceEntry) {
        if (!hasExcludeFilter(link)) return false;
        Filter filter = parse(link.excludeFilter());
        if (filter == null) return false;   // unparseable → fail open (don't drop)
        try {
            return filter.matchesEntry(sourceEntry);
        } catch (LDAPException e) {
            // e.g. a server-side-only extensible matching rule the offline
            // matcher can't evaluate. Fail open and warn rather than drop.
            log.warn("Exclude filter could not be evaluated offline for {} — treating as not excluded: {}",
                    sourceEntry.getDN(), e.getMessage());
            return false;
        }
    }

    /** Convenience: build an entry from a DN + attribute map and evaluate. */
    public static boolean isExcluded(ReplicationLinkSnapshot link, String sourceDn,
                                     Map<String, List<String>> attributes) {
        if (!hasExcludeFilter(link)) return false;
        return isExcluded(link, toEntry(sourceDn, attributes));
    }

    private static Entry toEntry(String dn, Map<String, List<String>> attributes) {
        List<Attribute> attrs = new ArrayList<>(attributes.size());
        attributes.forEach((name, values) ->
                attrs.add(new Attribute(name, values.toArray(new String[0]))));
        return new Entry(dn, attrs);
    }

    private static Filter parse(String filterString) {
        return CACHE.computeIfAbsent(filterString, s -> {
            try {
                return Optional.of(Filter.create(s));
            } catch (LDAPException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }
}
