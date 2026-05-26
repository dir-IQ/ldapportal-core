// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import com.ldapportal.addons.isva.entity.IsvaDemographicDeleteMode;
import com.ldapportal.addons.isva.entity.IsvaDeletePolicy;
import com.ldapportal.addons.isva.entity.IsvaGroupMemberTarget;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for PUT /api/v1/directories/{id}/isva-config.
 * All fields are required at the wire level so the client makes
 * a deliberate choice for each rather than relying on server-side
 * defaults that drift over time.
 *
 * <p>Linked-mode-only fields are nullable; the controller
 * validates that {@code managementDitBaseDn} is non-blank when
 * {@code topologyMode = LINKED}. The DB-level CHECK constraint
 * is the second line of defence.</p>
 */
public record UpsertIsvaConfigRequest(
        boolean enabled,

        @NotNull IsvaTopologyMode topologyMode,
        String secAuthority,

        @Min(1) int defaultValidUntilYears,

        @NotNull IsvaDeletePolicy deletePolicy,
        boolean requireSecGroup,

        // Linked-mode-only
        String managementDitBaseDn,
        String secuserRdnAttribute,
        IsvaGroupMemberTarget groupMemberTarget,
        IsvaDemographicDeleteMode onDemographicDelete) {
}
