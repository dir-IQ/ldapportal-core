// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.Membership;
import com.ldapportal.entity.enums.MembershipState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which rows an operator can delete together with the entries beneath their target entry. */
class NonLeafDeleteFailureTest {

    private static Membership row(MembershipState state, String reason) {
        Membership m = new Membership();
        m.setState(state);
        m.setFailReason(reason);
        return m;
    }

    @Test
    void aDeleteBlockedByEntriesBeneath_qualifies() {
        assertThat(RecomputeEngine.isNonLeafDeleteFailure(row(MembershipState.FAILED,
                RecomputeEngine.NONLEAF_PREFIX + "it has entries beneath it"))).isTrue();
    }

    @Test
    void aDeleteWithEntriesBeneathThatStoppedPartWay_qualifiesForARetry() {
        assertThat(RecomputeEngine.isNonLeafDeleteFailure(row(MembershipState.FAILED,
                RecomputeEngine.BENEATH_PREFIX + "couldn't delete cn=x beneath it"))).isTrue();
    }

    @Test
    void otherFailuresAndOtherStates_dont() {
        assertThat(RecomputeEngine.isNonLeafDeleteFailure(row(MembershipState.FAILED, "add failed: 65"))).isFalse();
        assertThat(RecomputeEngine.isNonLeafDeleteFailure(row(MembershipState.FAILED, null))).isFalse();
        assertThat(RecomputeEngine.isNonLeafDeleteFailure(row(MembershipState.APPLIED,
                RecomputeEngine.NONLEAF_PREFIX))).isFalse();
        assertThat(RecomputeEngine.isNonLeafDeleteFailure(null)).isFalse();
    }
}
