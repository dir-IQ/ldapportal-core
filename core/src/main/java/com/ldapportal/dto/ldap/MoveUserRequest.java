// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import jakarta.validation.constraints.NotBlank;

/** Moves a user entry to a new parent DN. */
public record MoveUserRequest(@NotBlank String newParentDn) {
}
