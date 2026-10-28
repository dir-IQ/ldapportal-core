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

    private static boolean sameValues(Attribute current, Attribute desired, Schema schema,
                                      Collection<String> dnAttrs) {
        MatchingRule rule = matchingRule(desired.getBaseName(), schema, dnAttrs);
        if (rule != null) {
            Set<String> a = normalized(current, rule);
            Set<String> b = normalized(desired, rule);
            if (a != null && b != null) {
                return a.equals(b);
            }
        }
        return exact(current).equals(exact(desired));
    }

    /**
     * The values a diff should aim for once reference values that don't
     * translate yet are accounted for: for each attribute in {@code unresolved}
     * that the desired set projects, the target's values that are exactly one
     * of the untranslated source DNs (compared as DNs) are added back. A member
     * whose entry isn't synced is then left where it is instead of removed;
     * one the source no longer lists is still removed.
     *
     * @param unresolved target attribute name → source DNs that didn't translate
     * @return the desired attributes to diff against, and per attribute the
     *         target values that were kept
     */
    public static Kept keepUnresolved(List<Attribute> desired, Map<String, List<String>> unresolved, Entry target) {
        if (unresolved.isEmpty() || target == null) {
            return new Kept(desired, Map.of());
        }
        MatchingRule rule = DistinguishedNameMatchingRule.getInstance();
        List<Attribute> out = new ArrayList<>(desired.size());
        Map<String, List<String>> kept = new LinkedHashMap<>();
        for (Attribute d : desired) {
            List<String> dns = valuesFor(unresolved, d.getBaseName());
            Attribute current = dns == null ? null : target.getAttribute(d.getName());
            if (current == null) {
                out.add(d);
                continue;
            }
            Set<String> wanted = new HashSet<>();
            for (String dn : dns) {
                String n = normalizeOrNull(rule, dn);
                if (n != null) {
                    wanted.add(n);
                }
            }
            Set<String> have = new HashSet<>();
            for (String v : d.getValues()) {
                have.add(normalizeOrExact(rule, v));
            }
            List<String> values = new ArrayList<>(List.of(d.getValues()));
            List<String> keptHere = new ArrayList<>();
            for (String v : current.getValues()) {
                String n = normalizeOrNull(rule, v);
                if (n != null && wanted.contains(n) && have.add(n)) {
                    values.add(v);
                    keptHere.add(v);
                }
            }
            if (keptHere.isEmpty()) {
                out.add(d);
            } else {
                out.add(new Attribute(d.getName(), values.toArray(new String[0])));
                kept.put(d.getName(), keptHere);
            }
        }
        return new Kept(out, kept.isEmpty() ? Map.of() : kept);
    }

    /**
     * Desired attributes after {@link #keepUnresolved}, and the target values it
     * kept per attribute (empty when none).
     */
    public record Kept(List<Attribute> desired, Map<String, List<String>> kept) {
    }

    private static List<String> valuesFor(Map<String, List<String>> byName, String baseName) {
        for (Map.Entry<String, List<String>> e : byName.entrySet()) {
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

    /** The values in the rule's normalized form, or {@code null} when one can't be normalized. */
    private static Set<String> normalized(Attribute attr, MatchingRule rule) {
        Set<String> out = new HashSet<>();
        for (byte[] value : attr.getValueByteArrays()) {
            try {
                out.add(rule.normalize(new ASN1OctetString(value)).stringValue());
            } catch (LDAPException ex) {
                return null; // e.g. a value that isn't a valid DN: compare exactly instead
            }
        }
        return out;
    }

    private static Set<String> exact(Attribute attr) {
        Set<String> out = new HashSet<>();
        for (byte[] value : attr.getValueByteArrays()) {
            out.add(Arrays.toString(value));
        }
        return out;
    }
}
