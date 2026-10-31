// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.matchingrules.DistinguishedNameMatchingRule;
import com.unboundid.ldap.matchingrules.MatchingRule;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import com.unboundid.ldap.sdk.schema.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes the {@link Modification}s that converge an existing target entry to
 * the projected desired attribute set. Reading the target and diffing (rather
 * than blindly replaying a source delta) keeps MODIFY exact and idempotent:
 * re-applying the same desired state yields an empty modification list.
 *
 * <p>DN-valued attributes — the set's reference attributes, and any the target
 * schema gives DN syntax — are compared by canonical DN, the way the directory
 * matches them: {@code secDN: cn=A, o=B} and {@code cn=a,o=b} name the same
 * entry, so no MODIFY is made for the DN's form alone. Every other value is
 * compared exactly, so an edit that only changes case ({@code john} →
 * {@code John}) still replicates even where the attribute matches case-ignore.
 * Attribute names are matched through the schema (aliases, case).
 *
 * <p>Excluded attributes (operational + the sync set's configured exclusions,
 * e.g. password values) are never touched — neither replaced nor deleted.
 */
public final class TargetEntryDiffer {

    private TargetEntryDiffer() {
    }

    /** Exact comparison, no schema and no known DN attributes. */
    public static List<Modification> diff(Entry target, List<Attribute> desired,
                                          Set<String> protectedAttrs) {
        return diff(target, desired, protectedAttrs, null, List.of());
    }

    /**
     * @param protectedAttrs lower-cased attribute names that must never be
     *                       deleted from the target (the sync set's effective
     *                       exclusions — see {@link SyncExcludedAttributes})
     * @param schema         the target directory's schema, or {@code null}
     * @param dnAttrs        attributes known to hold DNs (the set's reference
     *                       attributes), compared by canonical DN even without
     *                       a schema
     */
    public static List<Modification> diff(Entry target, List<Attribute> desired, Set<String> protectedAttrs,
                                          Schema schema, Collection<String> dnAttrs) {
        List<Modification> mods = new ArrayList<>();
        Map<String, Attribute> targetByName = new LinkedHashMap<>();
        for (Attribute a : target.getAttributes()) {
            targetByName.putIfAbsent(canonicalName(a.getName(), schema), a);
        }
        Set<String> desiredNames = new HashSet<>();

        for (Attribute d : desired) {
            String key = canonicalName(d.getName(), schema);
            desiredNames.add(key);
            Attribute current = targetByName.get(key);
            if (current == null || !sameValues(current, d, schema, dnAttrs)) {
                mods.add(new Modification(ModificationType.REPLACE, d.getName(), d.getValues()));
            }
        }

        for (Attribute current : target.getAttributes()) {
            String lower = current.getName().toLowerCase(Locale.ROOT);
            // Leave attributes the desired set keeps, and never delete an excluded
            // attribute (operational, or a sync-set exclusion like userPassword).
            if (desiredNames.contains(canonicalName(current.getName(), schema)) || protectedAttrs.contains(lower)) {
                continue;
            }
            mods.add(new Modification(ModificationType.DELETE, current.getName()));
        }
        return mods;
    }

    /**
     * The attribute's identity for matching: its schema name (so an alias
     * matches its primary name), lower-cased, plus its options in a stable
     * order ({@code cn;lang-en} is a different attribute from {@code cn}).
     */
    static String canonicalName(String name, Schema schema) {
        String base = Attribute.getBaseName(name);
        if (schema != null) {
            AttributeTypeDefinition type = schema.getAttributeType(base);
            if (type != null) {
                base = type.getNameOrOID();
            }
        }
        StringBuilder key = new StringBuilder(base.toLowerCase(Locale.ROOT));
        Set<String> options = new TreeSet<>();
        for (String option : Attribute.getOptions(name)) {
            options.add(option.toLowerCase(Locale.ROOT));
        }
        for (String option : options) {
            key.append(';').append(option);
        }
        return key.toString();
    }

    /** Whether the target holds exactly the desired values, by {@link EntryComparison}'s rules. */
    private static boolean sameValues(Attribute current, Attribute desired, Schema schema,
                                      Collection<String> dnAttrs) {
        return EntryComparison.values(desired, current, schema, dnAttrs, java.util.function.UnaryOperator.identity())
                .same();
    }

    /**
     * The values a diff should aim for once reference values that don't
     * translate yet are accounted for: for each attribute the desired set
     * projects, a target value that is exactly one of the candidate target DNs
     * (compared as DNs; see {@link UntranslatedReferenceTargets}) is added
     * back. A member whose entry isn't synced is then left where it is instead
     * of removed; one the source no longer lists is still removed.
     *
     * @param candidates attribute → (untranslated source DN → target DN it may be kept as)
     * @return the desired attributes to diff against, and per attribute the
     *         source DNs whose values were kept
     */
    public static Kept keepUnresolved(List<Attribute> desired, Map<String, Map<String, String>> candidates,
                                      Entry target) {
        if (candidates.isEmpty() || target == null) {
            return new Kept(desired, Map.of());
        }
        List<Attribute> out = new ArrayList<>(desired.size());
        Map<String, List<String>> kept = new LinkedHashMap<>();
        for (Attribute d : desired) {
            Map<String, String> bySource = valuesFor(candidates, d.getBaseName());
            Attribute current = bySource == null ? null : target.getAttribute(d.getName());
            if (current == null) {
                out.add(d);
                continue;
            }
            // Candidate target DN (normalized) → the source DN it stands for.
            Map<String, String> wanted = new LinkedHashMap<>();
            bySource.forEach((source, targetDn) -> {
                String n = normalizeOrNull(DN_RULE, targetDn);
                if (n != null) {
                    wanted.putIfAbsent(n, source);
                }
            });
            // Scan the target first: most groups have no candidate to keep, and
            // then their (possibly many) desired values needn't be normalized.
            Map<String, String> hits = new LinkedHashMap<>(); // target value → source DN
            for (String v : current.getValues()) {
                String n = normalizeOrNull(DN_RULE, v);
                if (n != null && wanted.containsKey(n)) {
                    hits.putIfAbsent(v, wanted.get(n));
                }
            }
            if (hits.isEmpty()) {
                out.add(d);
                continue;
            }
            Set<String> have = dnKeys(List.of(d.getValues()));
            List<String> values = new ArrayList<>(List.of(d.getValues()));
            List<String> keptSources = new ArrayList<>();
            hits.forEach((value, source) -> {
                if (have.add(normalizeOrExact(DN_RULE, value))) {
                    values.add(value);
                    keptSources.add(source);
                }
            });
            if (keptSources.isEmpty()) {
                out.add(d);
            } else {
                out.add(new Attribute(d.getName(), values.toArray(new String[0])));
                kept.put(d.getName(), keptSources);
            }
        }
        return new Kept(out, kept.isEmpty() ? Map.of() : kept);
    }

    /**
     * Desired attributes after {@link #keepUnresolved}, and per attribute the
     * untranslated source DNs whose target values were kept (empty when none).
     */
    public record Kept(List<Attribute> desired, Map<String, List<String>> kept) {
    }

    /** DN equality, for values compared as DNs. */
    static final MatchingRule DN_RULE = DistinguishedNameMatchingRule.getInstance();

    /** The values' DN-normalized forms (a value that isn't a valid DN as written). */
    static Set<String> dnKeys(Collection<String> values) {
        Set<String> out = new HashSet<>();
        for (String v : values) {
            out.add(normalizeOrExact(DN_RULE, v));
        }
        return out;
    }

    /** The entry for {@code baseName} in {@code byName}, matching names case-insensitively. */
    private static <V> V valuesFor(Map<String, V> byName, String baseName) {
        for (Map.Entry<String, V> e : byName.entrySet()) {
            if (Attribute.getBaseName(e.getKey()).equalsIgnoreCase(baseName)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Whether values of {@code attribute} hold DNs (one of {@code dnAttrs}, or DN
     * syntax in {@code schema}) and so compare as DNs rather than as text.
     */
    static boolean isDnValued(String attribute, Schema schema, Collection<String> dnAttrs) {
        return matchingRule(Attribute.getBaseName(attribute), schema, dnAttrs) != null;
    }

    /** A DN value's normalized form, for comparing values as DNs; the value itself if it isn't a valid DN. */
    static String normalizeOrExact(MatchingRule rule, String value) {
        String n = normalizeOrNull(rule, value);
        return n != null ? n : value;
    }

    private static String normalizeOrNull(MatchingRule rule, String value) {
        try {
            return rule.normalize(new ASN1OctetString(value)).stringValue();
        } catch (LDAPException ex) {
            return null;
        }
    }

    /** DN equality for a DN-valued attribute; {@code null} (an exact comparison) for any other. */
    static MatchingRule matchingRule(String baseName, Schema schema, Collection<String> dnAttrs) {
        for (String dnAttr : dnAttrs) {
            if (dnAttr.equalsIgnoreCase(baseName)) {
                return DistinguishedNameMatchingRule.getInstance();
            }
        }
        if (schema == null || schema.getAttributeType(baseName) == null) {
            return null;
        }
        MatchingRule rule = MatchingRule.selectEqualityMatchingRule(baseName, schema);
        return rule instanceof DistinguishedNameMatchingRule ? rule : null;
    }
}
