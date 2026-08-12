// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import com.ldapportal.addons.isva.IsvaSecUserPlans;
import com.ldapportal.addons.isva.entity.IsvaGroupMemberTarget;
import com.ldapportal.addons.isva.entity.IsvaRdnValueSource;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.SecUserAttribute;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Wire shape for the IsvaConfigController. Mirrors
 * {@link VendorIntegrationIsvaConfig} field-for-field. Audit
 * columns are read-only in responses; clients can't set them
 * via the upsert endpoint.
 */
public record IsvaConfigDto(
        Long version,
        boolean enabled,
        IsvaTopologyMode topologyMode,
        String secAuthority,
        String secLoginType,
        int defaultValidUntilYears,
        boolean requireSecGroup,

        // Applies to both modes
        List<String> secuserObjectClasses,
        List<String> secuserOverlayAttributes,

        // The effective per-attribute model — always populated (derived from
        // the legacy fields when no explicit model is stored), so the config
        // page always has a complete table to render and round-trip.
        List<SecUserAttribute> secuserAttributes,

        // Linked-mode-only — null in inline-mode responses
        String managementDitBaseDn,
        String secuserRdnAttribute,
        IsvaRdnValueSource secuserRdnValueSource,
        IsvaGroupMemberTarget groupMemberTarget,

        // Audit (read-only)
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String updatedBy) {

    public static IsvaConfigDto from(VendorIntegrationIsvaConfig entity) {
        return new IsvaConfigDto(
                entity.getVersion(),
                entity.isEnabled(),
                entity.getTopologyMode(),
                entity.getSecAuthority(),
                entity.getSecLoginType(),
                entity.getDefaultValidUntilYears(),
                entity.isRequireSecGroup(),
                entity.getSecuserObjectClasses(),
                entity.getSecuserOverlayAttributes(),
                IsvaSecUserPlans.effectiveAttributes(entity),
                entity.getManagementDitBaseDn(),
                entity.getSecuserRdnAttribute(),
                entity.getSecuserRdnValueSource(),
                entity.getGroupMemberTarget(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getUpdatedBy());
    }
}
