// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.playbook;

import com.ldapportal.dto.playbook.CreatePlaybookRequest.StepEntry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record UpdatePlaybookRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotNull String type,
        UUID profileId,
        boolean requireApproval,
        boolean enabled,
        List<@Valid StepEntry> steps) {
}
