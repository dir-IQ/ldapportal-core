// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.GroupMemberChange;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeResult;

import java.util.List;

/**
 * What a bulk user import needs to add each user to the groups listed in its
 * group-memberships column, under the caller's authority: resolve each value
 * (a group {@code cn} or DN) and write the adds through the governed
 * membership path.
 *
 * <p>{@link BulkUserService} only plans and reports; the caller
 * ({@link LdapOperationService}) supplies an implementation bound to one
 * directory and principal, see {@link ScopedUserImportGroups}.</p>
 */
interface UserImportGroups {

    /** Whether the caller may manage group members ({@code group.manage_members}). */
    boolean mayManageMembers();

    /** Resolves a group value — a {@code cn} or a full DN — within the caller's scope. */
    Target resolve(String value);

    /**
     * Adds the user to the given groups through
     * {@link LdapOperationService#applyMembershipChanges(java.util.UUID,
     * com.ldapportal.auth.AuthPrincipal, String, String, java.util.List)}:
     * approval gating, Separation-of-Duties checks, interceptors and a
     * per-change audit record, the same as adding the member by hand.
     */
    MembershipChangeResult add(String userDn, String userUid, List<MembershipChangeRequest.Change> changes);

    /** A resolved group; {@code problem} is set when the value couldn't be used. */
    record Target(String dn, String memberAttribute, GroupMemberChange.Outcome problem, String note) {
        static Target of(String dn, String memberAttribute) {
            return new Target(dn, memberAttribute, null, null);
        }

        static Target problem(GroupMemberChange.Outcome problem, String note) {
            return new Target(null, null, problem, note);
        }
    }
}
