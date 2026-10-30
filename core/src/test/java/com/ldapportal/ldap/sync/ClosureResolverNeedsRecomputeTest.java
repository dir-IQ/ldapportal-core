// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which referrers a reconcile's deferred fan-out queues again. */
class ClosureResolverNeedsRecomputeTest {

    /** The batch's last change was at position 10 of the run. */
    private static final int LAST_CHANGE = 10;

    @Test
    void ownReferrer_recomputedAfterTheBatchsLastChange_isSkipped() {
        assertThat(ClosureResolver.needsRecompute(11, LAST_CHANGE, true)).isFalse();
    }

    @Test
    void ownReferrer_thatIsTheBatchsLastChange_isSkipped() {
        // Its own recompute was the change: it saw everything before it.
        assertThat(ClosureResolver.needsRecompute(10, LAST_CHANGE, true)).isFalse();
    }

    @Test
    void ownReferrer_recomputedBeforeTheBatchsLastChange_isQueued() {
        assertThat(ClosureResolver.needsRecompute(9, LAST_CHANGE, true)).isTrue();
    }

    @Test
    void ownReferrer_theRunDidNotRecompute_isQueued() {
        assertThat(ClosureResolver.needsRecompute(null, LAST_CHANGE, true)).isTrue();
    }

    @Test
    void referrerInAnotherSet_isAlwaysQueued() {
        assertThat(ClosureResolver.needsRecompute(11, LAST_CHANGE, false)).isTrue();
        assertThat(ClosureResolver.needsRecompute(null, LAST_CHANGE, false)).isTrue();
    }
}
