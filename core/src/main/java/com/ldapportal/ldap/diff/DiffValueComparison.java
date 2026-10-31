// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.diff;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.matchingrules.DistinguishedNameMatchingRule;
import com.unboundid.ldap.matchingrules.MatchingRule;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import com.unboundid.ldap.sdk.schema.Schema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

/**
 * How the directory diff decides whether two copies of an attribute hold the
 * same values.
 *
 * <p>Deliberately independent of the sync engine's comparison
 * ({@code TargetEntryDiffer}): the diff is used to verify sync, so it must not
 * share sync's idea of "equal" — a bug there would otherwise hide itself.
 * {@code DirectoryDiffSyncAgreementTest} pins the two to the same answers.
 *
 * <p>DN-valued attributes (those named in {@code dnAttrs}, or whose equality
 * rule in the schema is DN matching) compare as DNs, using the LDAP library's
 * standard DN matching rule. A value that isn't a valid DN, and every other
 * attribute, compares byte for byte, so a case-only change counts.
 */
public final class DiffValueComparison {

    private DiffValueComparison() {
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
     * @param schema  decides which attributes hold DNs, or {@code null}
     * @param dnAttrs attributes treated as holding DNs even without a schema
     * @param mapA    rewrites each of side A's values before a DN comparison (e.g.
     *                its base swapped for side B's); values are reported as written
     */
    public static ValueDiff values(Attribute a, Attribute b, Schema schema, Collection<String> dnAttrs,
                                   UnaryOperator<String> mapA) {
        List<String> keysA = null;
        List<String> keysB = null;
        if (isDnValued(a.getBaseName(), schema, dnAttrs)) {
            keysA = dnKeys(a, mapA);
            keysB = dnKeys(b, UnaryOperator.identity());
        }
        if (keysA == null || keysB == null) {
            keysA = byteKeys(a);
            keysB = byteKeys(b);
        }
        String[] valuesA = a.getValues();
        String[] valuesB = b.getValues();
        Set<String> inA = new HashSet<>(keysA);
        Set<String> inB = new HashSet<>(keysB);
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

    /**
     * Which attribute a name refers to: its schema name (so an alias matches its
     * primary name), lower-cased, plus its options in a stable order.
     */
    public static String canonicalName(String name, Schema schema) {
        String base = Attribute.getBaseName(name);
        if (schema != null) {
            AttributeTypeDefinition type = schema.getAttributeType(base);
            if (type != null) {
                base = type.getNameOrOID();
            }
        }
        StringBuilder key = new StringBuilder(base.toLowerCase(Locale.ROOT));
        for (String option : new TreeSet<>(Attribute.getOptions(name).stream()
                .map(o -> o.toLowerCase(Locale.ROOT)).toList())) {
            key.append(';').append(option);
        }
        return key.toString();
    }

    /** Whether values of {@code baseName} hold DNs. */
    static boolean isDnValued(String baseName, Schema schema, Collection<String> dnAttrs) {
        for (String dnAttr : dnAttrs) {
            if (dnAttr.equalsIgnoreCase(baseName)) {
                return true;
            }
        }
        if (schema == null || schema.getAttributeType(baseName) == null) {
            return false;
        }
        return MatchingRule.selectEqualityMatchingRule(baseName, schema) instanceof DistinguishedNameMatchingRule;
    }

    /** Each value's normalized DN, in value order; {@code null} when one isn't a valid DN. */
    private static List<String> dnKeys(Attribute attr, UnaryOperator<String> map) {
        DistinguishedNameMatchingRule rule = DistinguishedNameMatchingRule.getInstance();
        List<String> out = new ArrayList<>();
        for (String value : attr.getValues()) {
            try {
                out.add(rule.normalize(new ASN1OctetString(map.apply(value))).stringValue());
            } catch (LDAPException ex) {
                return null;
            }
        }
        return out;
    }

    private static List<String> byteKeys(Attribute attr) {
        List<String> out = new ArrayList<>();
        for (byte[] value : attr.getValueByteArrays()) {
            out.add(Arrays.toString(value));
        }
        return out;
    }
}
