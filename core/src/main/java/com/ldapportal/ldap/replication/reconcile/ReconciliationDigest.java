// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stable digest over an entry's <em>managed</em> attributes, used by the
 * checksum (two-pass) reconciliation path (R-PP1, section 16). Equal managed
 * state — regardless of attribute-name case or multi-value ordering — yields
 * an equal digest, so an expected (source-mapped) entry and an equal actual
 * (target) entry hash identically without holding either's full attributes
 * in memory beyond pass 1.
 *
 * <p>Canonicalisation, mirroring {@link ReconciliationDiffer}'s comparison
 * semantics: drop the operational/exclusion set, lower-case names, sort
 * names, sort each value-set, then SHA-256 a <em>length-prefixed</em>
 * serialization (each token written as {@code <len>:<token>}) so no
 * delimiter can be forged by attribute content. Callers pass attributes that
 * are already DN/attribute-<em>mapped</em> (source side) so the digest is
 * computed in the target's namespace on both sides.
 */
public final class ReconciliationDigest {

    private ReconciliationDigest() {}

    /** SHA-256 hex digest of the managed attributes in {@code attrs}. */
    public static String digest(Map<String, List<String>> attrs) {
        Map<String, List<String>> managed = ReconciliationDiffer.stripExcluded(attrs);
        // Sorted by lower-cased name for case-insensitive, order-independent canonicalisation.
        TreeMap<String, List<String>> canon = new TreeMap<>();
        for (Map.Entry<String, List<String>> e : managed.entrySet()) {
            List<String> values = new ArrayList<>(e.getValue());
            values.sort(String::compareTo);
            canon.put(e.getKey().toLowerCase(Locale.ROOT), values);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : canon.entrySet()) {
            token(sb, e.getKey());
            sb.append(e.getValue().size()).append(':');
            for (String v : e.getValue()) token(sb, v);
        }
        return sha256Hex(sb.toString());
    }

    /** Append {@code <charLen>:<token>} — length-prefixing removes delimiter ambiguity. */
    private static void token(StringBuilder sb, String token) {
        sb.append(token.length()).append(':').append(token);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                   .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);   // never on a standard JRE
        }
    }
}
