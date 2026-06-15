// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Add multiple members to an LDAP group in a single request.
 */
public record BulkMemberRequest(
        @NotBlank String memberAttribute,
        @NotEmpty List<@NotBlank String> memberValues) {
}
