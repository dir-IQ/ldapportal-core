// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import com.ldapportal.entity.ProfileApprovalConfig;
import com.ldapportal.entity.enums.ApproverMode;

import java.util.UUID;

public record ApprovalConfigResponse(
        UUID id,
        UUID profileId,
        boolean requireApproval,
        ApproverMode approverMode,
        String approverGroupDn,
        Integer autoEscalateDays,
        UUID escalationAccountId) {

    public static ApprovalConfigResponse from(ProfileApprovalConfig c) {
        return new ApprovalConfigResponse(
                c.getId(),
                c.getProfile().getId(),
                c.isRequireApproval(),
                c.getApproverMode(),
                c.getApproverGroupDn(),
                c.getAutoEscalateDays(),
                c.getEscalationAccount() != null ? c.getEscalationAccount().getId() : null);
    }
}
