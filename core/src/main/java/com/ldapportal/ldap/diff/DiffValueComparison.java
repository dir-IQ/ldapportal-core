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
 *
 * <p>Except for the value naming each entry: when the attribute is in both
 * entries' RDNs and those RDN values match by the attribute's equality rule
 * (as the paired DNs do), a value matching its own side's RDN value is that
 * value on both sides. A directory keeps the RDN's form for it, so
 * {@code cn: john smith} at {@code cn=john smith,…} and {@code cn: John Smith}
 * at {@code cn=John Smith,…} are the same; only a rename changes its case.
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
        return values(a, b, schema, dnAttrs, mapA, null, null);
    }

    /**
     * As above, with each side's RDN value for this attribute ({@code null} when
     * it doesn't name that side's entry).
     */
    public static ValueDiff values(Attribute a, Attribute b, Schema schema, Collection<String> dnAttrs,
                                   UnaryOperator<String> mapA, String rdnA, String rdnB) {
        List<String> keysA = null;
        List<String> keysB = null;
        if (isDnValued(a.getBaseName(), schema, dnAttrs)) {
            keysA = dnKeys(a, mapA);
            keysB = dnKeys(b, UnaryOperator.identity());
        }
        if (keysA == null || keysB == null) {
            MatchingRule rule = rdnA != null && rdnB != null
                    ? MatchingRule.selectEqualityMatchingRule(a.getBaseName(), schema) : null;
            boolean sameRdn = rule != null && matches(rule, rdnA, rdnB);
            keysA = byteKeys(a, sameRdn ? rule : null, rdnA);
            keysB = byteKeys(b, sameRdn ? rule : null, rdnB);
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
    public static boolean isDnValued(String baseName, Schema schema, Collection<String> dnAttrs) {
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

    /** Each value byte for byte, except one matching {@code rdn} by {@code rule} (when given), which is the RDN value. */
    private static List<String> byteKeys(Attribute attr, MatchingRule rule, String rdn) {
        List<String> out = new ArrayList<>();
        for (byte[] value : attr.getValueByteArrays()) {
            out.add(rule != null && matches(rule, new String(value, java.nio.charset.StandardCharsets.UTF_8), rdn)
                    ? "rdn" : Arrays.toString(value));
        }
        return out;
    }

    private static boolean matches(MatchingRule rule, String x, String y) {
        try {
            return rule.valuesMatch(new ASN1OctetString(x), new ASN1OctetString(y));
        } catch (LDAPException ex) {
            return false;
        }
    }
}
