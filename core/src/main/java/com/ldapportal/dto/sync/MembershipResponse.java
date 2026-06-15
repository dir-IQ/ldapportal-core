// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.Membership;
import com.ldapportal.entity.enums.MembershipState;

import java.util.UUID;

/**
 * Inventory view of one {@link Membership} row — "where is identity X on the
 * target, in what state, and why (if FAILED/REVIEW)".
 */
public record MembershipResponse(
        UUID syncSetId,
        String identity,
        String sourceDn,
        String targetDn,
        MembershipState state,
        String failReason,
        Long lastSrcCursor,
        Long lastScanEpoch) {

    public static MembershipResponse of(Membership m) {
        return new MembershipResponse(m.getSyncSetId(), m.getIdentity(), m.getSourceDn(), m.getTargetDn(),
                m.getState(), m.getFailReason(), m.getLastSrcCursor(), m.getLastScanEpoch());
    }
}
