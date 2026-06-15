// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import com.ldapportal.entity.enums.ApproverMode;

import java.util.UUID;

public record ApprovalConfigRequest(
        boolean requireApproval,
        ApproverMode approverMode,
        String approverGroupDn,
        Integer autoEscalateDays,
        UUID escalationAccountId) {
}
