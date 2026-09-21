// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncSet;
import com.ldapportal.ldap.sync.identity.IdentityStrategy;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Filter;

/**
 * Resolves the effective identity for a sync set: a per-set {@code identityKey}
 * override when configured, otherwise the directory type's default attribute
 * from the {@link IdentityStrategy}. Normalization always runs through the
 * strategy so vendor-specific canonicalization is preserved.
 */
public final class SyncIdentity {

    private SyncIdentity() {
    }

    /** The attribute carrying the identity (set override, else strategy default). */
    public static String attribute(SyncSet set, IdentityStrategy strategy) {
        String override = set.getIdentityKey();
        if (override != null && !override.isBlank()) {
            return override;
        }
        return strategy.identityAttribute();
    }

    /** Extract + normalize the identity from a source entry, or null if absent. */
    public static String extract(SyncSet set, IdentityStrategy strategy, Entry entry) {
        String attr = attribute(set, strategy);
        if (attr == null || entry == null) {
            return null;
        }
        return strategy.extract(entry, attr);
    }

    /**
     * The search filter locating the source entry that carries {@code identity},
     * built by the strategy so a binary identity (AD {@code objectGUID}) matches
     * with a binary assertion value. Null when the set has no identity attribute.
     */
    public static Filter filter(SyncSet set, IdentityStrategy strategy, String identity) {
        String attr = attribute(set, strategy);
        if (attr == null) {
            return null;
        }
        return strategy.identityFilter(attr, identity);
    }
}
