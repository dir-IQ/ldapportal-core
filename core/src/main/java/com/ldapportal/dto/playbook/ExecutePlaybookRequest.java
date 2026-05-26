// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.playbook;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ExecutePlaybookRequest(
        @NotEmpty List<String> targetDns) {
}
