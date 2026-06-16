// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncSet;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the set of attribute names the sync engine must not copy from a
 * source entry nor delete from a target entry, for a given {@link SyncSet}.
 *
 * <p>The {@linkplain #DEFAULT_EXCLUDED defaults} cover two groups:
 * <ul>
 *   <li><b>Server-maintained operational attributes</b> (timestamps, the
 *       server-assigned uuid, structural metadata, password-policy state) —
 *       projecting or deleting them is meaningless at best and harmful at worst.</li>
 *   <li><b>Password value attributes</b> ({@code userPassword}, …) — a source
 *       password is stored hashed, and writing a pre-encoded hash to a target is
 *       rejected by a default password policy ({@code allow-pre-encoded-passwords:
 *       false} → constraint violation 19). Passwords belong to native directory
 *       replication, not attribute sync.</li>
 * </ul>
 *
 * <p>A {@link SyncSet} may override the list ({@link SyncSet#getExcludedAttributes()}):
 * <ul>
 *   <li>{@code null} (not configured) → the {@link #DEFAULT_EXCLUDED defaults}.</li>
 *   <li>a non-null list (including empty) → exactly that list; an empty list means
 *       "exclude nothing" (e.g. an operator deliberately propagating same-vendor
 *       password hashes, having set {@code allow-pre-encoded-passwords: true}).</li>
 * </ul>
 *
 * <p>Both projection ({@link MembershipFunction}) and the target differ
 * ({@link TargetEntryDiffer}) resolve through {@link #effectiveFor(SyncSet)} so
 * they can't diverge. Names are matched case-insensitively.
 */
public final class SyncExcludedAttributes {

    /**
     * Default excluded attribute names in canonical case (for display/seeding the
     * editor). Matched case-insensitively at runtime.
     */
    public static final List<String> DEFAULT_EXCLUDED = List.of(
            // Operational / server-maintained
            "entryUUID", "entryDN", "createTimestamp", "modifyTimestamp",
            "creatorsName", "modifiersName", "subschemaSubentry", "hasSubordinates",
            "numSubordinates", "structuralObjectClass", "entryCSN",
            "pwdChangedTime", "pwdAccountLockedTime", "ds-entry-unique-id",
            // Password value attributes (never sync — see class doc)
            "userPassword", "authPassword", "unicodePwd", "pwdHistory");

    private static final Set<String> DEFAULT_LOWER = lower(DEFAULT_EXCLUDED);

    private SyncExcludedAttributes() {
    }

    /**
     * The effective, lower-cased exclusion set for {@code set}: its configured
     * list when non-null, otherwise the defaults. Test membership directly
     * against this set with a lower-cased attribute name.
     */
    public static Set<String> effectiveFor(SyncSet set) {
        Collection<String> source = (set != null && set.getExcludedAttributes() != null)
                ? set.getExcludedAttributes()
                : DEFAULT_EXCLUDED;
        return lower(source);
    }

    private static Set<String> lower(Collection<String> names) {
        return names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(n -> n.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
