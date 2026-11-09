// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;

import java.util.Collection;

/**
 * Locates the directory container a set of entries lives in — used to give a
 * bulk operation's audit record a meaningful target DN instead of none.
 */
public final class DnContainers {

    private DnContainers() {
    }

    /**
     * The deepest container holding every entry in {@code dns}: the parent of a
     * single entry, or the closest common ancestor of the entries' parents.
     * Blank DNs are ignored.
     *
     * @return the container DN, or {@code null} when there is none to name —
     *         no DNs, a DN that isn't LDAP syntax (e.g. an Entra object id), a
     *         top-level entry, or entries with no common ancestor
     */
    public static String commonContainer(Collection<String> dns) {
        DN common = null;
        for (String dn : dns) {
            if (dn == null || dn.isBlank()) continue;
            DN parent;
            try {
                parent = new DN(dn).getParent();
            } catch (LDAPException e) {
                return null;
            }
            if (parent == null || parent.isNullDN()) return null;
            if (common == null) {
                common = parent;
                continue;
            }
            while (common != null && !common.isNullDN() && !parent.isDescendantOf(common, true)) {
                common = common.getParent();
            }
            if (common == null || common.isNullDN()) return null;
        }
        return common == null ? null : common.toString();
    }
}
