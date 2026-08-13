// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import com.ldapportal.addons.isva.entity.IsvaGroupMemberTarget;
import com.ldapportal.addons.isva.entity.IsvaRdnValueSource;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.SecUserAttribute;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

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
        String secLoginType,

        @Min(1) int defaultValidUntilYears,

        boolean requireSecGroup,

        // Applies to both modes — secUser is normalized in if omitted
        List<String> secuserObjectClasses,

        // Applies to both modes — the optional sec* overlay attributes to
        // write. null → server default (full set); normalized to the known
        // optional attributes server-side. Legacy: superseded by
        // secuserAttributes below when that is supplied.
        List<String> secuserOverlayAttributes,

        // Linked-mode-only
        String managementDitBaseDn,
        String secuserRdnAttribute,
        IsvaRdnValueSource secuserRdnValueSource,
        IsvaGroupMemberTarget groupMemberTarget,

        // The unified per-attribute model — one entry per secUser attribute
        // (name, enabled, literal-vs-computed, value/expression). When
        // supplied, this is the authoritative source for what a grant writes;
        // it's normalized to the canonical full set server-side. null → the
        // server derives an equivalent model from the legacy value fields.
        List<SecUserAttribute> secuserAttributes) {
}
