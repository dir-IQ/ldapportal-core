// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.GroupMemberChange;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.ldap.model.LdapGroup;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What a bulk group import needs from the directory, under the caller's
 * authority: resolve CSV member values, look groups up, and write member
 * changes through the governed membership path.
 *
 * <p>{@link BulkGroupService} only plans and reports; the caller
 * ({@link LdapOperationService}) supplies an implementation bound to one
 * directory and principal, see {@link ScopedGroupImportMembers}.</p>
 */
interface GroupImportMembers {

    /**
     * Called once, before any member value is resolved, when the file asks for
     * member changes; throws when the caller may not manage group members
     * ({@code group.manage_members}), so the whole request is refused.
     */
    void requireMemberChanges();

    /** Resolves a {@code members} value — a uid or a full DN — within the caller's scope. */
    Member resolveMember(String value);

    /** The entry at {@code dn}, or {@code null} when there is none. */
    ExistingGroup findGroup(String dn);

    /**
     * Applies one change through
     * {@link LdapOperationService#applyMembershipChanges(java.util.UUID,
     * com.ldapportal.auth.AuthPrincipal, String, String, java.util.List)}:
     * approval gating, Separation-of-Duties checks, interceptors and a
     * per-change audit record, the same as editing the membership by hand.
     */
    MembershipChangeResult.Item apply(Member member, String groupDn, String memberAttribute,
                                      MembershipChangeRequest.Op op);

    /** A resolved member value; {@code problem} is set when it couldn't be used. */
    record Member(String dn, String uid, GroupMemberChange.Outcome problem, String note) {
        static Member of(String dn, String uid) {
            return new Member(dn, uid, null, null);
        }

        static Member problem(GroupMemberChange.Outcome problem, String note) {
            return new Member(null, null, problem, note);
        }
    }

    /**
     * An existing entry at a group DN. {@code isGroup} is false when the entry
     * isn't one of the directory's group object classes. {@code attributes}
     * holds its current values, keyed by lower-case attribute name.
     */
    record ExistingGroup(String dn, boolean isGroup, String memberAttribute,
                         Set<String> memberDns, Set<String> memberUids,
                         Map<String, List<String>> attributes) {

        /**
         * Picks the attribute members are written to: whichever one the group
         * already uses, else what its object classes imply (groupOfUniqueNames
         * → uniqueMember, a pure posixGroup → memberUid, otherwise member).
         */
        static ExistingGroup of(LdapGroup g, boolean isGroup) {
            String attr;
            if (!g.getUniqueMember().isEmpty()) {
                attr = "uniqueMember";
            } else if (!g.getMember().isEmpty()) {
                attr = "member";
            } else if (!g.getMemberUid().isEmpty()) {
                attr = "memberUid";
            } else {
                Set<String> ocs = new HashSet<>();
                g.getValues("objectClass").forEach(oc -> ocs.add(oc.toLowerCase(Locale.ROOT)));
                if (ocs.contains("groupofuniquenames")) {
                    attr = "uniqueMember";
                } else if (ocs.contains("posixgroup") && !ocs.contains("groupofnames") && !ocs.contains("group")) {
                    attr = "memberUid";
                } else {
                    attr = "member";
                }
            }
            Set<String> dns = new HashSet<>();
            g.getMember().forEach(m -> dns.add(normalizeDn(m)));
            g.getUniqueMember().forEach(m -> dns.add(normalizeDn(m)));
            Set<String> uids = new HashSet<>();
            g.getMemberUid().forEach(m -> uids.add(m.toLowerCase(Locale.ROOT)));
            return new ExistingGroup(g.getDn(), isGroup, attr, dns, uids, lowerKeys(g.getAttributes()));
        }

        /** A group created earlier in the same file, with the members and attributes it was given. */
        static ExistingGroup created(String dn, String memberAttribute, Set<String> memberDns,
                                     Set<String> memberUids, Map<String, List<String>> attributes) {
            return new ExistingGroup(dn, true, memberAttribute, memberDns, memberUids, lowerKeys(attributes));
        }

        /** The group's current values for {@code attribute}; empty when it isn't set. */
        List<String> valuesOf(String attribute) {
            return attributes.getOrDefault(attribute.toLowerCase(Locale.ROOT), List.of());
        }

        private static Map<String, List<String>> lowerKeys(Map<String, List<String>> attrs) {
            Map<String, List<String>> out = new HashMap<>();
            if (attrs != null) attrs.forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
            return out;
        }

        boolean hasMember(Member m) {
            if ("memberUid".equalsIgnoreCase(memberAttribute)) {
                return m.uid() != null && memberUids.contains(m.uid().toLowerCase(Locale.ROOT));
            }
            return m.dn() != null && memberDns.contains(normalizeDn(m.dn()));
        }
    }

    /** Canonical DN for comparisons; falls back to lower-case on a malformed value. */
    static String normalizeDn(String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException e) {
            return dn.trim().toLowerCase(Locale.ROOT);
        }
    }
}
