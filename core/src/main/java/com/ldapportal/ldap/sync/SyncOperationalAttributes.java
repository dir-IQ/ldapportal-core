// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import java.util.Locale;
import java.util.Set;

/**
 * The single list of <em>operational / server-maintained</em> attribute names
 * the sync engine must never copy from a source entry nor delete from a target
 * entry. These are attributes the directory server owns (timestamps, the
 * server-assigned uuid, structural metadata, password-policy state) — projecting
 * or deleting them is meaningless at best and harmful at worst.
 *
 * <p>This used to be two hand-maintained {@code Set.of(...)} literals — one in
 * {@link MembershipFunction} (don't project from source) and one in
 * {@link TargetEntryDiffer} (don't delete from target) — that had already drifted
 * apart by one entry. They are the same concept and now share this constant so
 * they can't diverge again.
 *
 * <p>Note: well-behaved {@code "*"} reads return user attributes only, so the
 * target differ rarely even sees these; the exclusion is belt-and-suspenders for
 * servers/queries that surface operational attributes. Names are matched
 * case-insensitively — always test membership via {@link #contains(String)}.
 */
public final class SyncOperationalAttributes {

    /** Lower-cased operational attribute names. Use {@link #contains(String)}. */
    public static final Set<String> NAMES = Set.of(
            "entryuuid", "entrydn", "createtimestamp", "modifytimestamp",
            "creatorsname", "modifiersname", "subschemasubentry", "hassubordinates",
            "numsubordinates", "structuralobjectclass", "entrycsn",
            "pwdchangedtime", "pwdaccountlockedtime", "ds-entry-unique-id");

    private SyncOperationalAttributes() {
    }

    /** Case-insensitive membership test for an attribute name. */
    public static boolean contains(String attributeName) {
        return attributeName != null && NAMES.contains(attributeName.toLowerCase(Locale.ROOT));
    }
}
