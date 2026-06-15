// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import com.ldapportal.entity.ProfileLifecyclePolicy;
import com.ldapportal.entity.enums.ExpiryAction;

import java.util.UUID;

public record LifecyclePolicyResponse(
        UUID id,
        UUID profileId,
        Integer expiresAfterDays,
        Integer maxRenewals,
        Integer renewalDays,
        ExpiryAction onExpiryAction,
        String onExpiryMoveDn,
        boolean onExpiryRemoveGroups,
        boolean onExpiryNotify,
        Integer warningDaysBefore) {

    public static LifecyclePolicyResponse from(ProfileLifecyclePolicy p) {
        return new LifecyclePolicyResponse(
                p.getId(),
                p.getProfile().getId(),
                p.getExpiresAfterDays(),
                p.getMaxRenewals(),
                p.getRenewalDays(),
                p.getOnExpiryAction(),
                p.getOnExpiryMoveDn(),
                p.isOnExpiryRemoveGroups(),
                p.isOnExpiryNotify(),
                p.getWarningDaysBefore());
    }
}
