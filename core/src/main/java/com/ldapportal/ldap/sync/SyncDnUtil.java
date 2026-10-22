// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.RDN;

/**
 * DN helpers for the sync engine. Source DNs are stored and compared in
 * normalized form so reference remapping and DN-keyed lookups are robust to
 * spacing/case variation.
 */
public final class SyncDnUtil {

    private SyncDnUtil() {
    }

    /** Canonical (normalized) form of a DN, or the trimmed input if unparseable. */
    public static String normalize(String dn) {
        if (dn == null) {
            return null;
        }
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException ex) {
            return dn.trim();
        }
    }

    /**
     * Whether two DNs name the same entry: compared in canonical form, so case
     * and spacing don't matter. Unparseable values fall back to a
     * case-insensitive comparison; two nulls are equal.
     */
    public static boolean sameDn(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        return normalize(a).equalsIgnoreCase(normalize(b));
    }

    public static boolean isDn(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            new DN(value);
            return true;
        } catch (LDAPException ex) {
            return false;
        }
    }

    /**
     * The DN an entry ends up at after a MODIFY_DN: {@code newRdn} under
     * {@code newSuperiorDn} (a move) or under its old parent (a plain rename).
     * Returns {@code oldDn} unchanged if the parts can't be parsed (fail-open).
     */
    public static String afterModifyDn(String oldDn, String newRdn, String newSuperiorDn) {
        if (newRdn == null || newRdn.isBlank()) {
            return oldDn;
        }
        try {
            DN parent = (newSuperiorDn != null && !newSuperiorDn.isBlank())
                    ? new DN(newSuperiorDn) : new DN(oldDn).getParent();
            if (parent == null || parent.isNullDN()) {
                return new DN(new RDN(newRdn)).toString();
            }
            return new DN(new RDN(newRdn), parent).toString();
        } catch (LDAPException ex) {
            return oldDn;
        }
    }
}
