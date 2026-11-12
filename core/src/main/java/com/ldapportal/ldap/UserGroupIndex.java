// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.DirectoryObjectClassDefaults;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.util.CsvUtils;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Which groups each user belongs to, worked out from the group entries
 * themselves ({@code member}, {@code uniqueMember}, {@code memberUid}) rather
 * than a {@code memberOf}/{@code isMemberOf} attribute, which not every
 * directory maintains and which would also name groups outside the caller's
 * scope. Only the groups under the given search bases are indexed, so a
 * caller sees only memberships in groups they can see.
 *
 * <p>Two views: {@link #groupsOf} the groups a user is a direct member of,
 * and {@link #allGroupsOf} those plus every group they belong to through
 * nesting (a group listed as a member of another group). Nesting is followed
 * only through indexed groups, and a membership cycle is followed once.</p>
 *
 * <p>Each group is labelled by its {@code cn} when no other indexed group
 * shares it, else by its DN, so the label resolves back to the same group
 * (the user import accepts either).</p>
 */
public final class UserGroupIndex {

    /**
     * Key (and CSV header) of the direct-memberships column that user exports
     * and the user-listing reports add. The user import reads a column of this
     * name back as group memberships, so it never lists nested groups: a
     * re-import would make them direct.
     */
    public static final String COLUMN = "groups";

    /**
     * Key (and CSV header) of the column listing direct and nested
     * memberships. Read-only: the user import ignores it.
     */
    public static final String ALL_COLUMN = "allgroups";

    /** Normalized member DN (user or group) → normalized DNs of the groups listing it. */
    private final Map<String, List<String>> byMemberDn = new HashMap<>();
    /** Lower-cased uid → normalized DNs of the memberUid groups listing it. */
    private final Map<String, List<String>> byMemberUid = new HashMap<>();
    /** Normalized group DN → label. */
    private final Map<String, String> labels = new HashMap<>();
    /** Normalized group DN → the DN as the directory returned it. */
    private final Map<String, String> dns = new HashMap<>();

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
            String groupDn = normalize(g.getDn());
            index.labels.put(groupDn,
                    cn != null && cnCounts.get(cn.toLowerCase(Locale.ROOT)) == 1 ? cn : g.getDn());
            index.dns.put(groupDn, g.getDn());
            for (String m : g.getMember()) add(index.byMemberDn, normalize(m), groupDn);
            for (String m : g.getUniqueMember()) add(index.byMemberDn, normalize(m), groupDn);
            for (String m : g.getMemberUid()) add(index.byMemberUid, m.toLowerCase(Locale.ROOT), groupDn);
        }
        return index;
    }

    /**
     * The groups a user belongs to directly, by label, in the order the groups
     * were found. {@code uid} matches {@code memberUid} (posixGroup) groups and
     * may be null.
     */
    public List<String> groupsOf(String userDn, String uid) {
        return labelsOf(directGroupDns(userDn, uid));
    }

    /**
     * Whether the indexed group {@code groupDn} lists the user directly, by
     * DN (compared normalized) or, for a posixGroup, by {@code uid} (may be
     * null). False for a group that isn't indexed.
     */
    public boolean isDirectMember(String groupDn, String userDn, String uid) {
        return directGroupDns(userDn, uid).contains(normalize(groupDn));
    }

    /**
     * The groups a user belongs to directly or through nesting, by label:
     * the direct groups first, then each further level outwards.
     */
    public List<String> allGroupsOf(String userDn, String uid) {
        return labelsOf(allGroupDns(userDn, uid));
    }

    /**
     * {@link #allGroupsOf}, as the groups' DNs (as the directory returned
     * them) rather than labels.
     */
    public List<String> allGroupDnsOf(String userDn, String uid) {
        List<String> out = new ArrayList<>();
        for (String dn : allGroupDns(userDn, uid)) out.add(dns.get(dn));
        return out;
    }

    /**
     * The {@value #COLUMN} cell for a user: {@link #groupsOf} joined as a
     * multi-value cell ({@code |}, a literal pipe escaped), the form the
     * user import reads back.
     */
    public String cell(String userDn, String uid) {
        return CsvUtils.joinMultiValue(groupsOf(userDn, uid));
    }

    /** The {@value #ALL_COLUMN} cell for a user: {@link #allGroupsOf}, joined like {@link #cell}. */
    public String allCell(String userDn, String uid) {
        return CsvUtils.joinMultiValue(allGroupsOf(userDn, uid));
    }

    /**
     * {@code columns} with the {@value #COLUMN} and {@value #ALL_COLUMN}
     * columns last. A column that happens to have either name (any case)
     * gives way to it.
     */
    public static List<String> withColumns(List<String> columns) {
        List<String> out = new ArrayList<>(columns);
        out.removeIf(c -> c.equalsIgnoreCase(COLUMN) || c.equalsIgnoreCase(ALL_COLUMN));
        out.add(COLUMN);
        out.add(ALL_COLUMN);
        return out;
    }

    private List<String> directGroupDns(String userDn, String uid) {
        LinkedHashSet<String> out = new LinkedHashSet<>(byMemberDn.getOrDefault(normalize(userDn), List.of()));
        if (uid != null) out.addAll(byMemberUid.getOrDefault(uid.toLowerCase(Locale.ROOT), List.of()));
        return new ArrayList<>(out);
    }

    /** Breadth-first from the direct groups through the groups that list them. */
    private List<String> allGroupDns(String userDn, String uid) {
        LinkedHashSet<String> seen = new LinkedHashSet<>(directGroupDns(userDn, uid));
        ArrayDeque<String> queue = new ArrayDeque<>(seen);
        while (!queue.isEmpty()) {
            for (String parent : byMemberDn.getOrDefault(queue.poll(), List.of())) {
                if (seen.add(parent)) queue.add(parent);
            }
        }
        return new ArrayList<>(seen);
    }

    private List<String> labelsOf(List<String> groupDns) {
        List<String> out = new ArrayList<>(groupDns.size());
        for (String dn : groupDns) {
            String label = labels.get(dn);
            if (!out.contains(label)) out.add(label);
        }
        return out;
    }

    private static void add(Map<String, List<String>> map, String key, String groupDn) {
        List<String> groupDns = map.computeIfAbsent(key, k -> new ArrayList<>());
        if (!groupDns.contains(groupDn)) groupDns.add(groupDn);
    }

    private static String normalize(String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException e) {
            return dn.trim().toLowerCase(Locale.ROOT);
        }
    }
}
