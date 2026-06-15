// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.directory;

import jakarta.validation.constraints.NotBlank;

public record BaseDnRequest(
        @NotBlank String dn,
        int displayOrder) {
}
