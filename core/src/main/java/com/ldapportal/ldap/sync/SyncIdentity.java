// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncSet;
import com.ldapportal.ldap.sync.identity.IdentityStrategy;
import com.unboundid.ldap.sdk.Entry;

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
        return strategy.normalize(entry.getAttributeValue(attr));
    }
}
