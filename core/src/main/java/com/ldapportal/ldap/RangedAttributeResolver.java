// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPInterface;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Completes multi-valued attributes that the server returned in ranges.
 *
 * <p>Active Directory caps the number of values it returns for one
 * attribute in a single entry (MaxValRange, 1500 by default). Past that
 * cap it returns the first chunk under an attribute option —
 * {@code member;range=0-1499} — and expects the client to ask for the
 * rest with follow-up base-scope reads of {@code member;range=1500-*},
 * {@code member;range=3000-*}, … until the reply carries a
 * {@code range=N-*} option marking the final chunk. Without that, a large
 * group's membership silently truncates at the first chunk.</p>
 *
 * <p>The mechanism is keyed on the attribute option, not the vendor, so any
 * server implementing ranged retrieval is handled and every other server
 * costs nothing: an entry with no {@code ;range=} attribute is returned
 * untouched with no extra round-trip.</p>
 *
 * <p>Cost: one base-scope read per additional chunk, on the same borrowed
 * connection. A 50 000-member AD group is 33 extra reads.</p>
 */
@Slf4j
final class RangedAttributeResolver {

    private static final Pattern RANGE_OPTION = Pattern.compile("(?i)^range=(\\d+)-(\\d+|\\*)$");

    /** Hard stop so a server that never returns the final chunk cannot loop forever. */
    private static final int MAX_ROUNDS = 10_000;

    private RangedAttributeResolver() {}

    /**
     * Returns {@code entry} with every ranged attribute replaced by a single
     * complete attribute under its base name. Entries without ranged
     * attributes are returned as-is.
     */
    static SearchResultEntry resolve(LDAPInterface conn, SearchResultEntry entry) throws LDAPException {
        List<Attribute> plain = new ArrayList<>();
        Map<String, Accumulator> ranged = new LinkedHashMap<>();
        for (Attribute attr : entry.getAttributes()) {
            int[] range = rangeOf(attr);
            if (range == null) {
                plain.add(attr);
                continue;
            }
            ranged.computeIfAbsent(key(attr.getBaseName()), k -> new Accumulator(attr.getBaseName()))
                  .accept(attr, range);
        }
        if (ranged.isEmpty()) {
            return entry;
        }

        // AD emits an empty plain `member` next to `member;range=…`; fold any
        // such sibling into the accumulator so the merged attribute is the
        // only one left under that name.
        Iterator<Attribute> it = plain.iterator();
        while (it.hasNext()) {
            Attribute attr = it.next();
            Accumulator acc = ranged.get(key(attr.getBaseName()));
            if (acc != null) {
                acc.values.addAll(Arrays.asList(attr.getValues()));
                it.remove();
            }
        }

        for (Accumulator acc : ranged.values()) {
            fetchRemaining(conn, entry.getDN(), acc);
        }

        List<Attribute> merged = new ArrayList<>(plain);
        for (Accumulator acc : ranged.values()) {
            merged.add(new Attribute(acc.baseName, acc.values));
        }
        return new SearchResultEntry(entry.getDN(), merged, entry.getControls());
    }

    private static void fetchRemaining(LDAPInterface conn, String dn, Accumulator acc) throws LDAPException {
        int rounds = 0;
        while (!acc.complete && rounds++ < MAX_ROUNDS) {
            String wanted = acc.baseName + ";range=" + acc.nextStart + "-*";
            SearchResultEntry next = conn.searchForEntry(new SearchRequest(
                    dn, SearchScope.BASE, Filter.createPresenceFilter("objectClass"), wanted));
            if (next == null) {
                break;
            }
            boolean advanced = false;
            for (Attribute attr : next.getAttributes()) {
                if (!attr.getBaseName().equalsIgnoreCase(acc.baseName)) {
                    continue;
                }
                int[] range = rangeOf(attr);
                if (range == null) {
                    // Server handed back the whole attribute in one go.
                    acc.values.addAll(Arrays.asList(attr.getValues()));
                    acc.complete = true;
                    advanced = true;
                } else {
                    advanced |= acc.accept(attr, range);
                }
            }
            if (!advanced) {
                // Server ignored the range request or re-sent a chunk we
                // already hold; keep what we have rather than spin.
                break;
            }
        }
        if (!acc.complete) {
            log.warn("Ranged retrieval of {} on {} stopped early after {} values; membership may be incomplete",
                    acc.baseName, dn, acc.values.size());
        }
    }

    /**
     * Parses the {@code range=lo-hi} option on an attribute. Returns
     * {@code {lo, hi}} with {@code hi == -1} for the final ({@code *}) chunk,
     * or {@code null} when the attribute carries no range option.
     */
    private static int[] rangeOf(Attribute attr) {
        for (String option : attr.getOptions()) {
            Matcher m = RANGE_OPTION.matcher(option);
            if (m.matches()) {
                int lo = Integer.parseInt(m.group(1));
                int hi = "*".equals(m.group(2)) ? -1 : Integer.parseInt(m.group(2));
                return new int[] {lo, hi};
            }
        }
        return null;
    }

    private static String key(String baseName) {
        return baseName.toLowerCase(Locale.ROOT);
    }

    /** Values collected so far for one ranged attribute, plus where to resume. */
    private static final class Accumulator {
        final String baseName;
        final LinkedHashSet<String> values = new LinkedHashSet<>();
        int nextStart = 0;
        boolean complete = false;

        Accumulator(String baseName) {
            this.baseName = baseName;
        }

        /** Folds a chunk in; returns whether it moved the cursor forward. */
        boolean accept(Attribute attr, int[] range) {
            values.addAll(Arrays.asList(attr.getValues()));
            if (range[1] < 0) {
                complete = true;
                return true;
            }
            int resume = range[1] + 1;
            if (resume <= nextStart) {
                return false;
            }
            nextStart = resume;
            return true;
        }
    }
}
