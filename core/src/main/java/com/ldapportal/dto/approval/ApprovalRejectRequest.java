// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.approval;

import jakarta.validation.constraints.NotBlank;

public record ApprovalRejectRequest(@NotBlank String reason) {
}
