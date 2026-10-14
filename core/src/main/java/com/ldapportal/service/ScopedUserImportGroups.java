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
import com.ldapportal.ldap.model.LdapGroup;
import com.unboundid.ldap.sdk.Filter;
import org.springframework.security.access.AccessDeniedException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link UserImportGroups} for one directory and principal. A {@code cn} is
 * looked up among the groups in the caller's authorized OUs and must match
 * exactly one; a DN must be inside that scope and name a group. Lookups are
 * cached per value for the length of one import.
 */
final class ScopedUserImportGroups implements UserImportGroups {

    /** Writes adds for one user; bound to the governed membership path by the caller. */
    @FunctionalInterface
    interface MembershipWriter {
        MembershipChangeResult write(String memberDn, String memberUid,
                                     List<MembershipChangeRequest.Change> changes);
    }

    private final DirectoryConnection dc;
    private final UUID directoryId;
    private final AuthPrincipal principal;
    private final PermissionService permissionService;
    private final LdapGroupService groupService;
    private final MembershipWriter writer;
    private final Map<String, Target> targets = new HashMap<>();
    private Boolean mayManage;
    private List<String> bases;

    ScopedUserImportGroups(DirectoryConnection dc, UUID directoryId, AuthPrincipal principal,
                           PermissionService permissionService, LdapGroupService groupService,
                           MembershipWriter writer) {
        this.dc = dc;
        this.directoryId = directoryId;
        this.principal = principal;
        this.permissionService = permissionService;
        this.groupService = groupService;
        this.writer = writer;
    }

    @Override
    public boolean mayManageMembers() {
        if (mayManage == null) {
            try {
                permissionService.requireFeature(principal, directoryId, FeatureKey.GROUP_MANAGE_MEMBERS);
                mayManage = true;
            } catch (AccessDeniedException e) {
                mayManage = false;
            }
        }
        return mayManage;
    }

    @Override
    public Target resolve(String value) {
        return targets.computeIfAbsent(value.toLowerCase(Locale.ROOT), k -> lookup(value));
    }

    @Override
    public MembershipChangeResult add(String userDn, String userUid,
                                      List<MembershipChangeRequest.Change> changes) {
        return writer.write(userDn, userUid, changes);
    }

    private Target lookup(String value) {
        try {
            if (ScopedGroupImportMembers.looksLikeDn(value)) {
                if (!permissionService.isDnWithinScope(principal, directoryId, value)) {
                    return Target.problem(Outcome.OUT_OF_SCOPE, "Group is outside your authorized scope");
                }
                LdapGroup g = groupService.getGroup(dc, value);
                if (!isGroup(g)) {
                    return Target.problem(Outcome.INVALID, "The entry at this DN is not a group");
                }
                return Target.of(g.getDn(), memberAttribute(g));
            }
            String filter = "(&" + Filter.createEqualityFilter("cn", value)
                    + DirectoryObjectClassDefaults.groupSearchFilter(dc) + ")";
            // Keyed by normalized DN: overlapping authorized OUs can return the same group twice.
            Map<String, LdapGroup> found = new LinkedHashMap<>();
            for (String base : searchBases()) {
                for (LdapGroup g : groupService.searchGroups(dc, filter, base, 2, "objectClass")) {
                    found.putIfAbsent(GroupImportMembers.normalizeDn(g.getDn()), g);
                }
            }
            if (found.isEmpty()) {
                return Target.problem(Outcome.MEMBER_NOT_FOUND, "No group with cn=" + value);
            }
            if (found.size() > 1) {
                return Target.problem(Outcome.AMBIGUOUS,
                        "More than one group has cn=" + value + "; use the group's DN");
            }
            LdapGroup g = found.values().iterator().next();
            if (!permissionService.isDnWithinScope(principal, directoryId, g.getDn())) {
                return Target.problem(Outcome.OUT_OF_SCOPE, "Group is outside your authorized scope");
            }
            return Target.of(g.getDn(), memberAttribute(g));
        } catch (ResourceNotFoundException e) {
            return Target.problem(Outcome.MEMBER_NOT_FOUND, "No group at this DN");
        } catch (RuntimeException e) {
            return Target.problem(Outcome.INVALID, e.getMessage());
        }
    }

    private boolean isGroup(LdapGroup g) {
        Set<String> groupClasses = DirectoryObjectClassDefaults.effectiveGroupObjectClassSet(dc);
        return g.getValues("objectClass").stream()
                .anyMatch(oc -> groupClasses.contains(oc.toLowerCase(Locale.ROOT)));
    }

    /** The attribute members are written to, chosen the same way as the group import. */
    private static String memberAttribute(LdapGroup g) {
        return GroupImportMembers.ExistingGroup.of(g, true).memberAttribute();
    }

    private List<String> searchBases() {
        if (bases == null) {
            bases = permissionService.resolveSearchBaseDns(principal, directoryId, null);
        }
        return bases;
    }
}
