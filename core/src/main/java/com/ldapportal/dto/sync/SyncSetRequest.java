// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.SyncTransformRule;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.entity.enums.SyncScope;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Create/update payload for a {@link com.ldapportal.entity.SyncSet} — the unit of
 * selection + projection. Structural validation (DN syntax, filter syntax,
 * cadence floor) runs in the service.
 */
public record SyncSetRequest(
        @NotNull UUID linkId,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String objectScopeBaseDn,
        SyncScope objectScope,
        @Size(max = 255) String identityKey,
        @Size(max = 500) String targetBaseDn,
        @Size(max = 2000) String applicabilityFilter,
        @Size(max = 1000) String referenceAttributes,
        @Size(max = 255) String sourceAnchorAttribute,
        SyncDeletePolicy deletePolicy,
        List<SyncTransformRule> transformRules,
        @Min(60) Long reconcileCadenceSeconds,
        boolean enabled) {
}
