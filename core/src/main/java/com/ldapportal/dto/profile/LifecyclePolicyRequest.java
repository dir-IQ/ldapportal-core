// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.profile;

import com.ldapportal.entity.enums.ExpiryAction;

public record LifecyclePolicyRequest(
        Integer expiresAfterDays,
        Integer maxRenewals,
        Integer renewalDays,
        ExpiryAction onExpiryAction,
        String onExpiryMoveDn,
        boolean onExpiryRemoveGroups,
        boolean onExpiryNotify,
        Integer warningDaysBefore) {
}
