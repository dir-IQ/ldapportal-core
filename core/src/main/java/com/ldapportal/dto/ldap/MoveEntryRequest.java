// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import jakarta.validation.constraints.NotBlank;

public record MoveEntryRequest(@NotBlank String newParentDn) {
}
