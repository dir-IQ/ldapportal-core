// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.Attribute;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The output of the membership function for one source entry: either OUT (not a
 * member — absent, wrong objectClass, fails applicability, or unplaceable) or IN
 * with the projected desired target state.
 *
 * @param member       whether the entry is in the sync set's membership.
 * @param identity     the normalized correlation key (may be non-null even when
 *                     OUT, e.g. a present-but-excluded entry).
 * @param targetDn     desired target DN (IN only).
 * @param desiredAttrs projected desired attributes (IN only).
 * @param contentHash  hash over {@code (targetDn, desiredAttrs)} (IN only).
 * @param heldReferences reference attributes left out of {@code desiredAttrs}
 *                     because none of their values could be translated to a
 *                     target DN yet (their referents aren't synced), keyed by
 *                     target attribute name, each with the untranslated source
 *                     DNs. The target's current values of these attributes are
 *                     kept rather than deleted; a closure trigger re-drives the
 *                     entry once a referent lands (IN only, never null).
 * @param unresolvedReferences reference attributes projected with some values
 *                     but not all: the source DNs that couldn't be translated
 *                     yet, keyed by target attribute name. A target value that
 *                     is already exactly one of these DNs is kept (see
 *                     {@link TargetEntryDiffer#keepUnresolved}), so a member
 *                     isn't removed just because its entry isn't synced (IN
 *                     only, never null).
 */
public record MembershipDecision(boolean member,
                                 String identity,
                                 String targetDn,
                                 List<Attribute> desiredAttrs,
                                 byte[] contentHash,
                                 Map<String, List<String>> heldReferences,
                                 Map<String, List<String>> unresolvedReferences) {

    public static MembershipDecision out(String identity) {
        return new MembershipDecision(false, identity, null, null, null, Map.of(), Map.of());
    }

    public static MembershipDecision in(String identity, String targetDn,
                                        List<Attribute> desiredAttrs, byte[] contentHash) {
        return in(identity, targetDn, desiredAttrs, contentHash, Map.of(), Map.of());
    }

    public static MembershipDecision in(String identity, String targetDn, List<Attribute> desiredAttrs,
                                        byte[] contentHash, Map<String, List<String>> heldReferences,
                                        Map<String, List<String>> unresolvedReferences) {
        return new MembershipDecision(true, identity, targetDn, desiredAttrs, contentHash, heldReferences,
                unresolvedReferences);
    }

    /**
     * Lower-cased names of the {@link #heldReferences} attributes: the diff must
     * leave these alone on the target, as it does excluded attributes.
     */
    public Set<String> heldAttributeNames() {
        Set<String> names = new HashSet<>();
        for (String name : heldReferences.keySet()) {
            names.add(name.toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
