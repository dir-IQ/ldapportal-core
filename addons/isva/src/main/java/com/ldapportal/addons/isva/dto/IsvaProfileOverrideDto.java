// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import com.ldapportal.addons.isva.entity.IsvaProfileOverride;
import jakarta.validation.constraints.NotNull;

/**
 * The per-profile ISVA override. Used for both the GET response and
 * the PUT request body — the only field is the narrowing-only enum
 * ({@code INHERIT} | {@code FORCE_OFF}).
 */
public record IsvaProfileOverrideDto(
        @NotNull IsvaProfileOverride override) {

    public static IsvaProfileOverrideDto of(IsvaProfileOverride override) {
        return new IsvaProfileOverrideDto(override);
    }
}
