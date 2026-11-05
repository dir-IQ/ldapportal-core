// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.DirectoryObjectClassDefaults;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.util.CsvUtils;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which groups each user is a direct member of, worked out from the group
 * entries themselves ({@code member}, {@code uniqueMember}, {@code memberUid})
 * rather than a {@code memberOf}/{@code isMemberOf} attribute, which not every
 * directory maintains and which would also name groups outside the caller's
 * scope. Only the groups under the given search bases are indexed, so a
 * caller sees only memberships in groups they can see.
 *
 * <p>Each group is labelled by its {@code cn} when no other indexed group
 * shares it, else by its DN, so the label resolves back to the same group
 * (the user import accepts either).</p>
 */
public final class UserGroupIndex {

    /**
     * Key (and CSV header) of the groups column that user exports and the
     * user-listing reports add. The user import reads a column of this name
     * back as group memberships.
     */
    public static final String COLUMN = "groups";

    private final Map<String, List<String>> byMemberDn = new HashMap<>();
    private final Map<String, List<String>> byMemberUid = new HashMap<>();

    private UserGroupIndex() {}

    /**
     * Indexes the groups under {@code bases} (a {@code null} base means the
     * directory's base DN). A group found under two overlapping bases counts once.
     */
    public static UserGroupIndex build(LdapGroupService groupService, DirectoryConnection dc,
                                       Collection<String> bases) {
        Map<String, LdapGroup> groups = new LinkedHashMap<>();
        String filter = DirectoryObjectClassDefaults.groupSearchFilter(dc);
        for (String base : bases) {
            for (LdapGroup g : groupService.searchGroups(dc, filter, base,
                    "cn", "member", "uniqueMember", "memberUid")) {
                groups.putIfAbsent(normalize(g.getDn()), g);
            }
        }
        return of(groups.values());
    }

    /** Indexes the given groups. */
    public static UserGroupIndex of(Collection<LdapGroup> groups) {
        Map<String, Integer> cnCounts = new HashMap<>();
        for (LdapGroup g : groups) {
            String cn = g.getCn();
            if (cn != null) cnCounts.merge(cn.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        UserGroupIndex index = new UserGroupIndex();
        for (LdapGroup g : groups) {
            String cn = g.getCn();
            String label = cn != null && cnCounts.get(cn.toLowerCase(Locale.ROOT)) == 1 ? cn : g.getDn();
            for (String m : g.getMember()) index.add(index.byMemberDn, normalize(m), label);
            for (String m : g.getUniqueMember()) index.add(index.byMemberDn, normalize(m), label);
            for (String m : g.getMemberUid()) index.add(index.byMemberUid, m.toLowerCase(Locale.ROOT), label);
        }
        return index;
    }

    /**
     * The groups a user belongs to directly, by label, in the order the groups
     * were found. {@code uid} matches {@code memberUid} (posixGroup) groups and
     * may be null.
     */
    public List<String> groupsOf(String userDn, String uid) {
        List<String> out = new ArrayList<>(byMemberDn.getOrDefault(normalize(userDn), List.of()));
        if (uid != null) {
            for (String label : byMemberUid.getOrDefault(uid.toLowerCase(Locale.ROOT), List.of())) {
                if (!out.contains(label)) out.add(label);
            }
        }
        return out;
    }

    /**
     * The groups-column cell for a user: {@link #groupsOf} joined as a
     * multi-value cell ({@code |}, a literal pipe escaped), the form the
     * user import reads back.
     */
    public String cell(String userDn, String uid) {
        return CsvUtils.joinMultiValue(groupsOf(userDn, uid));
    }

    /**
     * {@code columns} with the groups column last. A column that happens to be
     * called {@value #COLUMN} (any case) gives way to it.
     */
    public static List<String> withColumn(List<String> columns) {
        List<String> out = new ArrayList<>(columns);
        out.removeIf(c -> c.equalsIgnoreCase(COLUMN));
        out.add(COLUMN);
        return out;
    }

    private void add(Map<String, List<String>> map, String key, String label) {
        List<String> labels = map.computeIfAbsent(key, k -> new ArrayList<>());
        if (!labels.contains(label)) labels.add(label);
    }

    private static String normalize(String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException e) {
            return dn.trim().toLowerCase(Locale.ROOT);
        }
    }
}
