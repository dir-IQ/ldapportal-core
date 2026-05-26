// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import java.util.List;

/**
 * Preview of group membership changes that would result from updating
 * a profile's effective group set.
 */
public record GroupChangePreview(
        List<UserGroupChange> changes,
        int totalUsersAffected) {

    public record UserGroupChange(
            String userDn,
            List<GroupChange> groupsToAdd,
            List<GroupChange> groupsToRemove) {
    }

    public record GroupChange(
            String groupDn,
            String memberAttribute) {
    }
}
