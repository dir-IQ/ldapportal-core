// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request body for updating an existing LDAP entry.
 * Contains one or more attribute modifications to apply atomically.
 */
public record UpdateEntryRequest(@NotEmpty List<@Valid AttributeModification> modifications) {
}
