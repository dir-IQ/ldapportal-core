// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Moves a user entry into another provisioning profile in the same directory.
 * The server reparents the entry under the destination profile's
 * {@code targetUserDn} and reconciles the user's profile-driven group
 * memberships; the operator never supplies a raw DN.
 */
public record MoveUserRequest(@NotNull UUID destinationProfileId) {
}
