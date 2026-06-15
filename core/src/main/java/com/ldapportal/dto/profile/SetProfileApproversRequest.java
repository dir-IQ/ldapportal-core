// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record SetProfileApproversRequest(
        @NotNull List<UUID> accountIds) {
}
