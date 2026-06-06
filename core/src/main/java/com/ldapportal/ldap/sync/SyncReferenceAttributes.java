// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncSet;

import java.util.Arrays;
import java.util.List;

/**
 * Resolves a sync set's DN-valued reference attributes — the attributes that
 * are remapped through the membership index during projection and reverse-
 * queried for closure. A sync set may declare its own comma-separated set;
 * otherwise the built-in defaults apply.
 */
public final class SyncReferenceAttributes {

    /** The standard DN-valued attributes carried across directory schemas. */
    public static final List<String> DEFAULTS =
            List.of("member", "uniqueMember", "manager", "owner", "secDN");

    private SyncReferenceAttributes() {
    }

    public static List<String> forSet(SyncSet set) {
        String configured = set.getReferenceAttributes();
        if (configured == null || configured.isBlank()) {
            return DEFAULTS;
        }
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static boolean containsIgnoreCase(List<String> attrs, String name) {
        for (String a : attrs) {
            if (a.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
