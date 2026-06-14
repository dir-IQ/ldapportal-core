// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Computes the {@link Modification}s that converge an existing target entry to
 * the projected desired attribute set. Reading the target and diffing (rather
 * than blindly replaying a source delta) keeps MODIFY exact and idempotent:
 * re-applying the same desired state yields an empty modification list.
 *
 * <p>Operational attributes the target server maintains are never touched.
 */
public final class TargetEntryDiffer {

    private TargetEntryDiffer() {
    }

    public static List<Modification> diff(Entry target, List<Attribute> desired) {
        List<Modification> mods = new ArrayList<>();
        Set<String> desiredNames = new LinkedHashSet<>();

        for (Attribute d : desired) {
            desiredNames.add(d.getName().toLowerCase(Locale.ROOT));
            Attribute current = target.getAttribute(d.getName());
            if (current == null || !sameValues(current, d)) {
                mods.add(new Modification(ModificationType.REPLACE, d.getName(), d.getValues()));
            }
        }

        for (Attribute current : target.getAttributes()) {
            String lower = current.getName().toLowerCase(Locale.ROOT);
            // Leave attributes the desired set keeps, and never delete a
            // server-maintained operational attribute (see SyncOperationalAttributes).
            if (desiredNames.contains(lower) || SyncOperationalAttributes.contains(lower)) {
                continue;
            }
            mods.add(new Modification(ModificationType.DELETE, current.getName()));
        }
        return mods;
    }

    private static boolean sameValues(Attribute a, Attribute b) {
        Set<String> av = new LinkedHashSet<>(List.of(a.getValues()));
        Set<String> bv = new LinkedHashSet<>(List.of(b.getValues()));
        return av.equals(bv);
    }
}
