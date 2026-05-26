// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * A single LDAP attribute modification as part of an update request.
 *
 * <p>{@code operation} must be one of: {@code ADD}, {@code REPLACE}, {@code DELETE}.
 * {@code values} may be empty for {@code DELETE} to remove all values of the attribute.</p>
 */
public record AttributeModification(
        @NotNull Operation operation,
        @NotBlank String attribute,
        List<String> values) {

    public enum Operation {
        ADD, REPLACE, DELETE
    }
}
