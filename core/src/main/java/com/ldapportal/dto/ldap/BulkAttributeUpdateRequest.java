// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for applying the same attribute modifications to multiple LDAP entries.
 *
 * <p>Each DN in {@code dns} will have every modification in {@code modifications} applied.</p>
 */
public record BulkAttributeUpdateRequest(
        @NotEmpty List<String> dns,
        @NotEmpty List<@Valid AttributeModification> modifications) {
}
