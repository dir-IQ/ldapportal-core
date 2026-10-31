// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.matchingrules.MatchingRule;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * How two directories' copies of one attribute compare, value by value: the
 * one definition of "different" that both the sync engine
 * ({@link TargetEntryDiffer}) and the directory diff use.
 *
 * <p>DN-valued attributes (those named in {@code dnAttrs}, or with DN syntax in
 * the schema) compare by canonical DN, the way the directory matches them; a
 * value that isn't a valid DN as written, and every other attribute, compares
 * exactly, so a case-only change still counts.
 */
public final class EntryComparison {

    private EntryComparison() {
    }

    /**
     * One attribute's values on each side: those only on side A, only on side
     * B, and on both (as side A writes them).
     */
    public record ValueDiff(List<String> onlyA, List<String> onlyB, List<String> both) {
        /** Whether both sides hold the same values. */
        public boolean same() {
            return onlyA.isEmpty() && onlyB.isEmpty();
        }
    }

    /**
     * Compare {@code a} and {@code b}, two copies of one attribute.
     *
     * @param schema  the schema that decides which attributes hold DNs, or {@code null}
     * @param dnAttrs attributes known to hold DNs even without a schema
     * @param mapA    rewrites a DN value of side A before it's compared (e.g. its
     *                base swapped for side B's); the value is reported as written
     */
    public static ValueDiff values(Attribute a, Attribute b, Schema schema, Collection<String> dnAttrs,
                                   UnaryOperator<String> mapA) {
        MatchingRule rule = TargetEntryDiffer.matchingRule(a.getBaseName(), schema, dnAttrs);
        List<String> keysA = null;
        List<String> keysB = null;
        if (rule != null) {
            keysA = normalized(a, rule, mapA);
            keysB = normalized(b, rule, UnaryOperator.identity());
        }
        if (keysA == null || keysB == null) {
            keysA = exact(a);
            keysB = exact(b);
        }
        String[] valuesA = a.getValues();
        String[] valuesB = b.getValues();
        Set<String> inB = new HashSet<>(keysB);
        Set<String> inA = new HashSet<>(keysA);
        List<String> onlyA = new ArrayList<>();
        List<String> both = new ArrayList<>();
        for (int i = 0; i < valuesA.length; i++) {
            (inB.contains(keysA.get(i)) ? both : onlyA).add(valuesA[i]);
        }
        List<String> onlyB = new ArrayList<>();
        for (int i = 0; i < valuesB.length; i++) {
            if (!inA.contains(keysB.get(i))) {
                onlyB.add(valuesB[i]);
            }
        }
        return new ValueDiff(onlyA, onlyB, both);
    }

    /** The attribute's identity for matching across sides: its schema name, lower-cased, plus its options. */
    public static String canonicalName(String name, Schema schema) {
        return TargetEntryDiffer.canonicalName(name, schema);
    }

    /** Whether values of {@code attribute} hold DNs, and so compare as DNs. */
    public static boolean isDnValued(String attribute, Schema schema, Collection<String> dnAttrs) {
        return TargetEntryDiffer.isDnValued(attribute, schema, dnAttrs);
    }

    /** Each value's key in the rule's normalized form, in value order; {@code null} when one can't be normalized. */
    private static List<String> normalized(Attribute attr, MatchingRule rule, UnaryOperator<String> map) {
        List<String> out = new ArrayList<>();
        for (String value : attr.getValues()) {
            try {
                out.add(rule.normalize(new ASN1OctetString(map.apply(value))).stringValue());
            } catch (LDAPException ex) {
                return null; // e.g. a value that isn't a valid DN: compare exactly instead
            }
        }
        return out;
    }

    private static List<String> exact(Attribute attr) {
        List<String> out = new ArrayList<>();
        for (byte[] value : attr.getValueByteArrays()) {
            out.add(Arrays.toString(value));
        }
        return out;
    }
}
