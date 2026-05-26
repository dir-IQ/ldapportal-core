// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import jakarta.validation.constraints.NotBlank;

/**
 * Add or remove a single member from an LDAP group.
 *
 * <p>{@code memberAttribute} is the LDAP attribute that holds membership values,
 * e.g. {@code member}, {@code uniqueMember}, or {@code memberUid}.
 * {@code memberValue} is the DN (or UID string for posixGroup) of the member.</p>
 */
public record MemberRequest(
        @NotBlank String memberAttribute,
        @NotBlank String memberValue) {
}
