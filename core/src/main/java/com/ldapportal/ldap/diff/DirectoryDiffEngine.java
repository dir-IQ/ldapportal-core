// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.diff;

import com.ldapportal.entity.enums.DirectoryDiffCategory;
import com.ldapportal.entity.enums.DirectoryDiffPairing;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.schema.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Pairs the entries read from two sides and sorts each into a
 * {@link DirectoryDiffCategory}, comparing a pair attribute by attribute with
 * {@link DiffValueComparison}. Nothing here comes from the sync engine, so the
 * diff can check sync's work independently. No I/O: the caller reads
 * both sides and stores what this hands it.
 */
public final class DirectoryDiffEngine {

    /** Values kept per side per attribute in a result; the counts say how many there were. */
    public static final int VALUE_CAP = 100;

    /**
     * Attributes that hold DNs in the standard schemas, compared as DNs even
     * when a directory's schema couldn't be read.
     */
    public static final List<String> KNOWN_DN_ATTRIBUTES = List.of("member", "uniqueMember", "owner", "manager",
            "seeAlso", "secretary", "roleOccupant", "memberOf", "distinguishedName");

    private DirectoryDiffEngine() {
    }

    /**
     * @param pairing       how entries pair
     * @param pairAttribute the attribute pairing by {@link DirectoryDiffPairing#ATTRIBUTE} uses
     * @param compared      whether an attribute (lower-case base name) is compared
     * @param schema        decides which attributes hold DNs (compared as DNs), or {@code null}
     * @param rebaseA       an entry's or a DN value's place on side B, from side A's DN
     *                      ({@code null} when it isn't under side A's base); pairs by DN, and
     *                      DN values are compared after it
     */
    public record Options(DirectoryDiffPairing pairing, String pairAttribute, Predicate<String> compared,
                          Schema schema, UnaryOperator<String> rebaseA) {
    }

    /** One attribute that differs: up to {@link #VALUE_CAP} values only on A, only on B, and on both. */
    public record AttributeDiff(String name, List<String> onlyA, int onlyACount, List<String> onlyB, int onlyBCount,
                                List<String> both, int bothCount) {
    }

    /** One attribute equal on both sides, with its values (as side A writes them). */
    public record SameAttribute(String name, List<String> values) {
    }

    /** One result: an entry on one side, or a pair, and how they compare. */
    public record Result(DirectoryDiffCategory category, String dnA, String dnB, List<AttributeDiff> diffs,
                         List<SameAttribute> same, String note) {
    }

    /**
     * Pair and compare {@code a} with {@code b}, handing {@code sink} one result
     * per entry or pair: side A's entries in order, then side B's left over.
     *
     * @param progress  told how many entries (of both sides) each result accounts for
     * @param cancelled checked between entries; when it's true the comparison stops
     * @return false when it stopped because {@code cancelled} turned true
     */
    public static boolean compare(List<? extends Entry> a, List<? extends Entry> b, Options options,
                                  Consumer<Result> sink, java.util.function.IntConsumer progress,
                                  BooleanSupplier cancelled) {
        Map<String, List<Entry>> byKeyB = new HashMap<>();
        for (Entry e : b) {
            String key = keyB(e, options);
            if (key != null) {
                byKeyB.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }
        }
        Map<String, Integer> keyCountA = new HashMap<>();
        for (Entry e : a) {
            String key = keyA(e, options);
            if (key != null) {
                keyCountA.merge(key, 1, Integer::sum);
            }
        }

        Set<Entry> matchedB = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Entry ea : a) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            String key = keyA(ea, options);
            if (key == null) {
                sink.accept(onlyA(ea, noKeyNote(ea, options, "A")));
                progress.accept(1);
                continue;
            }
            List<Entry> candidates = byKeyB.getOrDefault(key, List.of());
            int onA = keyCountA.getOrDefault(key, 0);
            if (onA > 1 || candidates.size() > 1) {
                sink.accept(onlyA(ea, sharedNote(options, onA, candidates.size())));
                progress.accept(1);
                continue;
            }
            if (candidates.isEmpty()) {
                sink.accept(onlyA(ea, null));
                progress.accept(1);
                continue;
            }
            Entry eb = candidates.get(0);
            matchedB.add(eb);
            sink.accept(comparePair(ea, eb, options));
            progress.accept(2);
        }
        for (Entry eb : b) {
            if (cancelled.getAsBoolean()) {
                return false;
            }
            if (matchedB.contains(eb)) {
                continue;
            }
            String key = keyB(eb, options);
            String note = null;
            if (key == null) {
                note = noKeyNote(eb, options, "B");
            } else {
                int onB = byKeyB.getOrDefault(key, List.of()).size();
                int onA = keyCountA.getOrDefault(key, 0);
                if (onA > 1 || onB > 1) {
                    note = sharedNote(options, onA, onB);
                }
            }
            sink.accept(new Result(DirectoryDiffCategory.ONLY_B, null, eb.getDN(), List.of(), List.of(), note));
            progress.accept(1);
        }
        return true;
    }

    /** Compare one pair, attribute by attribute: SAME or DIFFERENT. */
    public static Result comparePair(Entry a, Entry b, Options options) {
        Map<String, Attribute> attrsA = byName(a, options);
        Map<String, Attribute> attrsB = byName(b, options);
        Set<String> names = new java.util.LinkedHashSet<>(attrsA.keySet());
        names.addAll(attrsB.keySet());
        UnaryOperator<String> mapA = value -> {
            String rebased = options.rebaseA() == null ? null : options.rebaseA().apply(value);
            return rebased != null ? rebased : value;
        };
        List<AttributeDiff> diffs = new ArrayList<>();
        List<SameAttribute> same = new ArrayList<>();
        for (String key : names) {
            Attribute va = attrsA.get(key);
            Attribute vb = attrsB.get(key);
            if (va != null && vb != null) {
                DiffValueComparison.ValueDiff d = DiffValueComparison.values(va, vb, options.schema(), KNOWN_DN_ATTRIBUTES, mapA);
                if (d.same()) {
                    same.add(new SameAttribute(va.getName(), List.of(va.getValues())));
                } else {
                    diffs.add(diff(va.getName(), d.onlyA(), d.onlyB(), d.both()));
                }
            } else if (va != null) {
                diffs.add(diff(va.getName(), List.of(va.getValues()), List.of(), List.of()));
            } else {
                diffs.add(diff(vb.getName(), List.of(), List.of(vb.getValues()), List.of()));
            }
        }
        DirectoryDiffCategory category = diffs.isEmpty() ? DirectoryDiffCategory.SAME : DirectoryDiffCategory.DIFFERENT;
        return new Result(category, a.getDN(), b.getDN(), diffs, same, null);
    }

    /** The compared attributes of {@code e}, keyed by their canonical name. */
    private static Map<String, Attribute> byName(Entry e, Options options) {
        Map<String, Attribute> out = new LinkedHashMap<>();
        for (Attribute attr : e.getAttributes()) {
            if (options.compared().test(attr.getBaseName().toLowerCase(Locale.ROOT))) {
                out.putIfAbsent(DiffValueComparison.canonicalName(attr.getName(), options.schema()), attr);
            }
        }
        return out;
    }

    private static AttributeDiff diff(String name, List<String> onlyA, List<String> onlyB, List<String> both) {
        return new AttributeDiff(name, cap(onlyA), onlyA.size(), cap(onlyB), onlyB.size(), cap(both), both.size());
    }

    private static List<String> cap(List<String> values) {
        return values.size() <= VALUE_CAP ? List.copyOf(values) : List.copyOf(values.subList(0, VALUE_CAP));
    }

    private static Result onlyA(Entry e, String note) {
        return new Result(DirectoryDiffCategory.ONLY_A, e.getDN(), null, List.of(), List.of(), note);
    }

    private static String keyA(Entry e, Options options) {
        if (options.pairing() == DirectoryDiffPairing.DN) {
            String placed = options.rebaseA() == null ? e.getDN() : options.rebaseA().apply(e.getDN());
            return placed == null ? null : normalizeDn(placed);
        }
        return attributeKey(e, options.pairAttribute());
    }

    private static String keyB(Entry e, Options options) {
        if (options.pairing() == DirectoryDiffPairing.DN) {
            return normalizeDn(e.getDN());
        }
        return attributeKey(e, options.pairAttribute());
    }

    /** The pairing attribute's one value, trimmed and lower-cased; {@code null} without exactly one. */
    private static String attributeKey(Entry e, String attribute) {
        String[] values = e.getAttributeValues(attribute);
        if (values == null || values.length != 1 || values[0].isBlank()) {
            return null;
        }
        return values[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String noKeyNote(Entry e, Options options, String side) {
        if (options.pairing() == DirectoryDiffPairing.DN) {
            return "Not under side " + side + "'s base DN";
        }
        String[] values = e.getAttributeValues(options.pairAttribute());
        return values == null || values.length == 0
                ? "No " + options.pairAttribute() + " value to pair by"
                : options.pairAttribute() + " has " + values.length + " values, so it can't pair";
    }

    private static String sharedNote(Options options, int onA, int onB) {
        String what = options.pairing() == DirectoryDiffPairing.DN ? "DN" : options.pairAttribute() + " value";
        return "Its " + what + " is shared by " + onA + " entr" + (onA == 1 ? "y" : "ies") + " on A and " + onB
                + " on B, so it can't pair";
    }

    /** Never compared or stored, whatever a request says. */
    public static final Set<String> SECRET_ATTRIBUTES = Set.of("userpassword", "authpassword", "unicodepwd",
            "pwdhistory", "sambantpassword", "sambalmpassword", "clearpassword", "supplementalcredentials",
            "dbcspwd", "lmpwdhistory", "ntpwdhistory");

    /**
     * Server-maintained attributes left out by default. The diff keeps its own
     * list rather than borrowing the sync engine's exclusions, so an attribute
     * sync wrongly skips still shows up as missing.
     */
    public static final Set<String> OPERATIONAL_ATTRIBUTES = Set.of("entryuuid", "entrydn", "entrycsn",
            "createtimestamp", "modifytimestamp", "creatorsname", "modifiersname", "subschemasubentry",
            "hassubordinates", "numsubordinates", "structuralobjectclass", "pwdchangedtime",
            "pwdaccountlockedtime", "pwdfailuretime", "pwdgraceusetime", "ds-entry-unique-id", "nsuniqueid",
            "objectguid", "objectsid", "usncreated", "usnchanged", "whencreated", "whenchanged",
            "dscorepropagationdata", "contextcsn");

    /**
     * Whether an attribute (lower-case base name) is compared: one of
     * {@code only} when that's given, else any but the
     * {@link #OPERATIONAL_ATTRIBUTES}; never one of
     * {@code ignore}, and never a password.
     */
    public static Predicate<String> compared(java.util.Collection<String> only, java.util.Collection<String> ignore) {
        Set<String> onlyNames = lower(only);
        Set<String> ignored = lower(ignore);
        return name -> !SECRET_ATTRIBUTES.contains(name) && !ignored.contains(name)
                && (onlyNames.isEmpty() ? !OPERATIONAL_ATTRIBUTES.contains(name) : onlyNames.contains(name));
    }

    /**
     * A DN's place on side B: side A's base swapped for side B's (the DN itself
     * when the bases are equal); {@code null} for a DN outside side A's base or
     * one that doesn't parse.
     */
    public static UnaryOperator<String> rebase(String baseA, String baseB) {
        DN fromBase;
        DN toBase;
        try {
            fromBase = new DN(baseA);
            toBase = new DN(baseB);
        } catch (LDAPException ex) {
            return dn -> null;
        }
        return value -> {
            try {
                DN dn = new DN(value);
                if (!dn.isDescendantOf(fromBase, true)) {
                    return null;
                }
                if (fromBase.equals(toBase)) {
                    return value;
                }
                RDN[] rdns = dn.getRDNs();
                int kept = rdns.length - fromBase.getRDNs().length;
                StringBuilder out = new StringBuilder();
                for (int i = 0; i < kept; i++) {
                    out.append(rdns[i]).append(',');
                }
                return out.append(toBase).toString();
            } catch (LDAPException ex) {
                return null;
            }
        };
    }

    /** A DN in normalized form, for pairing; lower-cased as written when it doesn't parse. */
    private static String normalizeDn(String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException ex) {
            return dn.trim().toLowerCase(Locale.ROOT);
        }
    }

    private static Set<String> lower(java.util.Collection<String> values) {
        Set<String> out = new HashSet<>();
        if (values == null) {
            return out;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
