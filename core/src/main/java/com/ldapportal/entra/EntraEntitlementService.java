// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

import com.ldapportal.entra.entity.EntraGroup;
import com.ldapportal.entra.entity.EntraGroupMembership;
import com.ldapportal.entra.entity.EntraUser;
import com.ldapportal.entra.repository.EntraGroupMembershipRepository;
import com.ldapportal.entra.repository.EntraGroupRepository;
import com.ldapportal.entra.repository.EntraUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Provides entitlement data from the Entra ID local cache
 * for auditor portal, evidence packages, and reporting.
 */
@Service
@RequiredArgsConstructor
public class EntraEntitlementService {

    private final EntraUserRepository userRepo;
    private final EntraGroupRepository groupRepo;
    private final EntraGroupMembershipRepository membershipRepo;

    @Transactional(readOnly = true)
    public List<UserEntitlement> getUserEntitlements(UUID directoryId) {
        List<EntraUser> users = userRepo.findAllByDirectoryId(directoryId);
        Map<String, String> groupNames = buildGroupNameMap(directoryId);

        List<UserEntitlement> result = new ArrayList<>();
        for (EntraUser user : users) {
            List<EntraGroupMembership> memberships =
                    membershipRepo.findAllByDirectoryIdAndUserObjectId(directoryId, user.getEntraObjectId());
            List<String> groups = memberships.stream()
                    .map(m -> groupNames.getOrDefault(m.getGroupObjectId(), m.getGroupObjectId()))
                    .sorted()
                    .toList();
            result.add(new UserEntitlement(
                    user.getEntraObjectId(),
                    user.getDisplayName(),
                    user.getUserPrincipalName(),
                    user.getMail(),
                    user.getAccountEnabled(),
                    groups));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<GroupDetail> getGroups(UUID directoryId) {
        List<EntraGroup> groups = groupRepo.findAllByDirectoryId(directoryId);
        List<GroupDetail> result = new ArrayList<>();
        for (EntraGroup g : groups) {
            long memberCount = membershipRepo.countByDirectoryIdAndGroupObjectId(
                    directoryId, g.getEntraObjectId());
            result.add(new GroupDetail(
                    g.getEntraObjectId(), g.getDisplayName(), g.getDescription(), (int) memberCount));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<String> getGroupMemberIds(UUID directoryId, String groupObjectId) {
        return membershipRepo.findAllByDirectoryIdAndGroupObjectId(directoryId, groupObjectId)
                .stream().map(EntraGroupMembership::getUserObjectId).toList();
    }

    private Map<String, String> buildGroupNameMap(UUID directoryId) {
        return groupRepo.findAllByDirectoryId(directoryId).stream()
                .collect(Collectors.toMap(EntraGroup::getEntraObjectId, g ->
                        g.getDisplayName() != null ? g.getDisplayName() : g.getEntraObjectId()));
    }

    public record UserEntitlement(
            String objectId, String displayName, String userPrincipalName,
            String mail, Boolean accountEnabled, List<String> groups) {}

    public record GroupDetail(
            String objectId, String displayName, String description, int memberCount) {}
}
