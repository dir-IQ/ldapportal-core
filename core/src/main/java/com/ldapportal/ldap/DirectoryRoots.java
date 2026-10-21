// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;

import java.util.Locale;

/**
 * Questions about a directory's roots ({@link DirectoryConnection#getBaseDns()}):
 * which root holds a DN, and what a DN's parent is within them. DNs are
 * compared structurally (case and spacing don't matter), falling back to a
 * normalized string comparison for a DN that doesn't parse.
 */
public final class DirectoryRoots {

    private DirectoryRoots() {
    }

    /** Whether the directory has more than one root. */
    public static boolean isMultiRoot(DirectoryConnection dc) {
        return dc.getBaseDns().size() > 1;
    }

    /**
     * The root that {@code dn} is, or lies under — the deepest one when roots
     * nest — or {@code null} when it's outside every root.
     */
    public static String rootOf(DirectoryConnection dc, String dn) {
        if (dn == null || dn.isBlank()) {
            return null;
        }
        String best = null;
        int bestDepth = -1;
        for (String root : dc.getBaseDns()) {
            if (isAtOrUnder(dn, root)) {
                int depth = depth(root);
                if (depth > bestDepth) {
                    best = root;
                    bestDepth = depth;
                }
            }
        }
        return best;
    }

    /** Whether {@code dn} is a root or lies under one. */
    public static boolean isWithin(DirectoryConnection dc, String dn) {
        return rootOf(dc, dn) != null;
    }

    /** Whether {@code dn} is exactly one of the roots. */
    public static boolean isRoot(DirectoryConnection dc, String dn) {
        if (dn == null || dn.isBlank()) {
            return false;
        }
        for (String root : dc.getBaseDns()) {
            if (sameDn(dn, root)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The parent of {@code dn} for the browser: {@code null} when {@code dn} is
     * a root (or has no parent), meaning "the roots themselves".
     */
    public static String parentWithinRoots(DirectoryConnection dc, String dn) {
        if (dn == null || dn.isBlank() || isRoot(dc, dn)) {
            return null;
        }
        try {
            DN parent = new DN(dn).getParent();
            return parent == null || parent.isNullDN() ? null : parent.toString();
        } catch (LDAPException e) {
            int idx = dn.indexOf(',');
            return (idx < 0 || idx + 1 >= dn.length()) ? null : dn.substring(idx + 1);
        }
    }

    /** Whether two DNs are the same or one lies under the other. */
    public static boolean nests(String a, String b) {
        return isAtOrUnder(a, b) || isAtOrUnder(b, a);
    }

    /** Whether {@code dn} equals {@code ancestor} or is a descendant of it. */
    static boolean isAtOrUnder(String dn, String ancestor) {
        try {
            return new DN(dn).isDescendantOf(new DN(ancestor), true);
        } catch (LDAPException e) {
            String d = normalize(dn);
            String a = normalize(ancestor);
            return d.equals(a) || d.endsWith("," + a);
        }
    }

    private static boolean sameDn(String a, String b) {
        try {
            return new DN(a).equals(new DN(b));
        } catch (LDAPException e) {
            return normalize(a).equals(normalize(b));
        }
    }

    private static int depth(String dn) {
        try {
            return new DN(dn).getRDNs().length;
        } catch (LDAPException e) {
            return normalize(dn).split(",").length;
        }
    }

    private static String normalize(String dn) {
        return dn.trim().toLowerCase(Locale.ROOT).replaceAll("\\s*,\\s*", ",");
    }
}
