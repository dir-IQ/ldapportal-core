// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.dto.csv.GroupMemberChange.Outcome;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.DirectoryObjectClassDefaults;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapGroupService;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.model.LdapUser;
import com.unboundid.ldap.sdk.Filter;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link GroupImportMembers} for one directory and principal. Member values
 * and groups are looked up once per distinct value and cached for the import.
 *
 * <ul>
 *   <li>A value with an {@code attr=value} form is a DN; it must lie within
 *       the caller's scope and exist (any entry, so nested groups work).</li>
 *   <li>Anything else is a uid, searched for among users under the caller's
 *       search bases (their profile OUs; the directory root for a superadmin).
 *       No match, or more than one, is reported per member.</li>
 * </ul>
 */
final class ScopedGroupImportMembers implements GroupImportMembers {

    /** Writes one member's changes through the governed membership path. */
    @FunctionalInterface
    interface MembershipWriter {
        MembershipChangeResult write(String memberDn, String memberUid,
                                     List<MembershipChangeRequest.Change> changes);
    }

    private final DirectoryConnection dc;
    private final UUID directoryId;
    private final AuthPrincipal principal;
    private final PermissionService permissionService;
    private final LdapUserService userService;
    private final LdapGroupService groupService;
    private final MembershipWriter writer;
    private List<String> bases;
    private final Map<String, Member> members = new HashMap<>();

    ScopedGroupImportMembers(DirectoryConnection dc, UUID directoryId, AuthPrincipal principal,
                             PermissionService permissionService, LdapUserService userService,
                             LdapGroupService groupService, MembershipWriter writer) {
        this.dc = dc;
        this.directoryId = directoryId;
        this.principal = principal;
        this.permissionService = permissionService;
        this.userService = userService;
        this.groupService = groupService;
        this.writer = writer;
    }

    @Override
    public void requireMemberChanges() {
        permissionService.requireFeature(principal, directoryId, FeatureKey.GROUP_MANAGE_MEMBERS);
    }

    @Override
    public Member resolveMember(String value) {
        return members.computeIfAbsent(value.toLowerCase(Locale.ROOT), k -> lookupMember(value));
    }

    @Override
    public ExistingGroup findGroup(String dn) {
        try {
            // All user attributes: members, object classes, and the current
            // values a row's attributes are compared against.
            LdapGroup g = groupService.getGroup(dc, dn);
            Set<String> groupClasses = DirectoryObjectClassDefaults.effectiveGroupObjectClassSet(dc);
            boolean isGroup = g.getValues("objectClass").stream()
                    .anyMatch(oc -> groupClasses.contains(oc.toLowerCase(Locale.ROOT)));
            return ExistingGroup.of(g, isGroup);
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    @Override
    public MembershipChangeResult.Item apply(Member member, String groupDn, String memberAttribute,
                                             MembershipChangeRequest.Op op) {
        MembershipChangeResult result = writer.write(member.dn(), member.uid(),
                List.of(new MembershipChangeRequest.Change(groupDn, memberAttribute, op)));
        return result.items().isEmpty() ? null : result.items().get(0);
    }

    private Member lookupMember(String value) {
        try {
            if (looksLikeDn(value)) {
                if (!permissionService.isDnWithinScope(principal, directoryId, value)) {
                    return Member.problem(Outcome.OUT_OF_SCOPE, "Member is outside your authorized scope");
                }
                LdapUser entry = userService.getUser(dc, value, "uid");
                return Member.of(entry.getDn(), entry.getFirstValue("uid"));
            }
            String filter = "(&" + Filter.createEqualityFilter("uid", value)
                    + DirectoryObjectClassDefaults.userSearchFilter(dc) + ")";
            Set<String> dns = new LinkedHashSet<>();
            for (String base : searchBases()) {
                userService.searchUsers(dc, filter, base, 2, "1.1").forEach(u -> dns.add(u.getDn()));
            }
            if (dns.isEmpty()) {
                return Member.problem(Outcome.MEMBER_NOT_FOUND, "No user with uid=" + value);
            }
            if (dns.size() > 1) {
                return Member.problem(Outcome.AMBIGUOUS, "uid=" + value + " matches more than one user");
            }
            String dn = dns.iterator().next();
            if (!permissionService.isDnWithinScope(principal, directoryId, dn)) {
                return Member.problem(Outcome.OUT_OF_SCOPE, "Member is outside your authorized scope");
            }
            return Member.of(dn, value);
        } catch (ResourceNotFoundException e) {
            return Member.problem(Outcome.MEMBER_NOT_FOUND, "No entry at this DN");
        } catch (RuntimeException e) {
            return Member.problem(Outcome.INVALID, e.getMessage());
        }
    }

    private List<String> searchBases() {
        if (bases == null) {
            bases = permissionService.resolveSearchBaseDns(principal, directoryId, null);
        }
        return bases;
    }

    /** A value is treated as a DN when it has an attribute=value form. */
    static boolean looksLikeDn(String v) {
        return v.indexOf('=') > 0;
    }
}
