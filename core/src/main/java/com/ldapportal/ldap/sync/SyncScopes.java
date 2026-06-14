// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.SyncScope;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchScope;

/**
 * Shared resolution of a {@link SyncSet}'s object scope — the single source of
 * truth for "where does this set look in the source tree, and is a given DN
 * inside it?"
 *
 * <p>Extracted because the same two snippets were copy-pasted across the engine
 * ({@link RecomputeEngine}), the reconciler ({@link MembershipReconciler}), the
 * closure resolver ({@link ClosureResolver}), and both feeds
 * ({@link SyncChangelogPoller}, {@link SyncWriteCaptor}). Keeping the "unset
 * scope defaults to SUB" and "null base means unbounded" rules in one place
 * stops them drifting apart silently.
 */
public final class SyncScopes {

    private SyncScopes() {
    }

    /**
     * The LDAP {@link SearchScope} for a set's {@link SyncSet#getObjectScope()},
     * defaulting to {@link SearchScope#SUB} when the set leaves it unset
     * (subtree is the safe, most-inclusive default for enumeration).
     */
    public static SearchScope searchScope(SyncSet set) {
        SyncScope s = set.getObjectScope() == null ? SyncScope.SUB : set.getObjectScope();
        return switch (s) {
            case BASE -> SearchScope.BASE;
            case ONE -> SearchScope.ONE;
            case SUB -> SearchScope.SUB;
        };
    }

    /**
     * Whether {@code dn} falls within the set's object-scope base DN.
     *
     * <p>A {@code null} {@code objectScopeBaseDn} means "no base bound" → every
     * DN is in scope (the link/source base is the only bound). An unparseable
     * {@code dn} is treated as out of scope (fail-closed) so a malformed feed
     * entry can't be projected. The base itself counts as in scope
     * (descendant-or-equal).
     */
    public static boolean inScope(SyncSet set, String dn) {
        String base = set.getObjectScopeBaseDn();
        if (base == null) {
            return true;
        }
        try {
            return new DN(dn).isDescendantOf(new DN(base), true);
        } catch (LDAPException ex) {
            return false;
        }
    }
}
