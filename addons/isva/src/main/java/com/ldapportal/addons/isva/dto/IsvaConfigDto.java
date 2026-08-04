// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import com.ldapportal.addons.isva.entity.IsvaDemographicDeleteMode;
import com.ldapportal.addons.isva.entity.IsvaDeletePolicy;
import com.ldapportal.addons.isva.entity.IsvaGroupMemberTarget;
import com.ldapportal.addons.isva.entity.IsvaRdnValueSource;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
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
        IsvaDeletePolicy deletePolicy,
        boolean requireSecGroup,

        // Applies to both modes
        List<String> secuserObjectClasses,

        // Linked-mode-only — null in inline-mode responses
        String managementDitBaseDn,
        String secuserRdnAttribute,
        IsvaRdnValueSource secuserRdnValueSource,
        IsvaGroupMemberTarget groupMemberTarget,
        IsvaDemographicDeleteMode onDemographicDelete,

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
                entity.getDeletePolicy(),
                entity.isRequireSecGroup(),
                entity.getSecuserObjectClasses(),
                entity.getManagementDitBaseDn(),
                entity.getSecuserRdnAttribute(),
                entity.getSecuserRdnValueSource(),
                entity.getGroupMemberTarget(),
                entity.getOnDemographicDelete(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getUpdatedBy());
    }
}
