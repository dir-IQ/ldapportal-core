// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import java.util.List;

/**
 * Request to apply selected group membership changes.
 * Each entry represents a single user + group combination.
 */
public record SelectiveGroupChangeRequest(
        List<GroupMembershipEntry> entries
) {
    public record GroupMembershipEntry(
            String userDn,
            String groupDn,
            String memberAttribute
    ) {}
}
