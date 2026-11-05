// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.Attribute;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Computes a stable content hash over the <em>projected desired</em> target
 * state (target DN + attributes). The hash drives churn suppression: a recompute
 * whose desired hash equals the stored {@code membership.content_hash} is a
 * no-op, which both avoids redundant target writes and terminates closure
 * cascades once projected outputs stabilize.
 *
 * <p>Canonicalization makes the hash invariant to incidental ordering: the
 * target DN is lower-cased, attribute names are lower-cased and sorted, and each
 * attribute's values are sorted. It deliberately hashes the projected output
 * only, so source-side changes to un-synced attributes don't churn the target.
 *
 * <p>Every component is length-prefixed so the canonical form is unambiguous —
 * no value content (spaces, newlines, {@code '='}) can alias one
 * attribute/value boundary into another and collide two distinct desired states.
 */
public final class SyncContentHash {

    private SyncContentHash() {
    }

    public static byte[] of(String targetDn, List<Attribute> desiredAttrs) {
        return of(targetDn, desiredAttrs, java.util.Map.of());
    }

    /**
     * As {@link #of(String, List)}, also covering the reference values that
     * couldn't be translated (attribute → source DNs). They aren't in the
     * desired attributes, yet whether the target keeps them depends on them:
     * without them a source that drops such a member would hash the same, the
     * hash gate would skip the entry, and the target would keep the member for
     * good. An entry with none hashes exactly as before.
     */
    public static byte[] of(String targetDn, List<Attribute> desiredAttrs,
                            java.util.Map<String, List<String>> untranslated) {
        StringBuilder canonical = new StringBuilder();
        lengthPrefixed(canonical, targetDn == null ? "" : targetDn.toLowerCase(Locale.ROOT));

        List<String> attrLines = new ArrayList<>(desiredAttrs.size());
        for (Attribute a : desiredAttrs) {
            List<String> values = new ArrayList<>(List.of(a.getValues()));
            values.sort(null);
            StringBuilder line = new StringBuilder();
            lengthPrefixed(line, a.getName().toLowerCase(Locale.ROOT));
            for (String v : values) {
                lengthPrefixed(line, v);
            }
            attrLines.add(line.toString());
        }
        attrLines.sort(null);
        for (String line : attrLines) {
            lengthPrefixed(canonical, line);
        }
        // A separate section (no attribute name contains NUL), so these can't
        // collide with a projected attribute of the same name.
        List<String> refLines = new ArrayList<>(untranslated.size());
        untranslated.forEach((name, dns) -> {
            List<String> values = new ArrayList<>(dns.size());
            for (String dn : dns) {
                values.add(SyncDnUtil.normalize(dn));
            }
            values.sort(null);
            StringBuilder line = new StringBuilder();
            lengthPrefixed(line, "\u0000untranslated:" + name.toLowerCase(Locale.ROOT));
            for (String v : values) {
                lengthPrefixed(line, v);
            }
            refLines.add(line.toString());
        });
        refLines.sort(null);
        for (String line : refLines) {
            lengthPrefixed(canonical, line);
        }

        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS-referenced security providers.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void lengthPrefixed(StringBuilder sb, String s) {
        sb.append(s.length()).append(':').append(s);
    }
}
