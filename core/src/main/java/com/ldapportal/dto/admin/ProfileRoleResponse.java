// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.admin;

import com.ldapportal.entity.AdminProfileRole;
import com.ldapportal.entity.enums.BaseRole;

import java.util.UUID;

public record ProfileRoleResponse(
        UUID id,
        UUID profileId,
        String profileName,
        UUID directoryId,
        BaseRole baseRole) {

    public static ProfileRoleResponse from(AdminProfileRole r) {
        return new ProfileRoleResponse(
                r.getId(),
                r.getProfile().getId(),
                r.getProfile().getName(),
                r.getProfile().getDirectory().getId(),
                r.getBaseRole());
    }
}
