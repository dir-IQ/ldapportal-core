// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/**
 * Shared request body for creating an LDAP user or group entry.
 *
 * <p>{@code dn} is the full distinguished name of the new entry.
 * {@code attributes} is an open attribute map; keys are LDAP attribute names
 * (case-insensitive) and values are multi-valued string lists.</p>
 */
public record CreateEntryRequest(
        @NotBlank String dn,
        @NotEmpty Map<String, List<String>> attributes) {
}
