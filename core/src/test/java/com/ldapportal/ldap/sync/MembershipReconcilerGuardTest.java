// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the blast-radius / zero-enumeration delete guard
 * ({@link MembershipReconciler#guardFor}). The end-to-end apply path is covered
 * by {@code SyncEngineIntegrationTest}; this pins the suppression thresholds.
 */
class MembershipReconcilerGuardTest {

    @Test
    void incompleteScan_neverTrips() {
        // A failed enumeration already skips the sweep — nothing to suppress.
        assertThat(MembershipReconciler.guardFor(false, 0, 100, 100).tripped()).isFalse();
    }

    @Test
    void zeroEnumeration_withManagedRows_trips() {
        var g = MembershipReconciler.guardFor(true, 0, 5, 5);
        assertThat(g.tripped()).isTrue();
        assertThat(g.reason()).contains("no entries");
    }

    @Test
    void zeroEnumeration_withNoManagedRows_doesNotTrip() {
        // Fresh/empty set legitimately has nothing on either side.
        assertThat(MembershipReconciler.guardFor(true, 0, 0, 0).tripped()).isFalse();
    }

    @Test
    void deletesOverAbsoluteCap_trip() {
        int over = MembershipReconciler.DELETE_GUARD_MAX_ABSOLUTE + 1;
        var g = MembershipReconciler.guardFor(true, 1000, 1000, over);
        assertThat(g.tripped()).isTrue();
        assertThat(g.reason()).contains("absolute cap");
    }

    @Test
    void deletesOverPercent_onLargeSet_trip() {
        // 30% of 100 managed, above the 20% threshold and the population floor.
        var g = MembershipReconciler.guardFor(true, 100, 100, 30);
        assertThat(g.tripped()).isTrue();
        assertThat(g.reason()).contains("%");
    }

    @Test
    void smallSet_singleDelete_doesNotTrip() {
        // Below the population floor, the percentage guard must not fire — a
        // 1-of-3 delete is legitimate, not a misconfiguration.
        assertThat(MembershipReconciler.guardFor(true, 2, 3, 1).tripped()).isFalse();
    }

    @Test
    void largeSet_smallDeleteFraction_doesNotTrip() {
        // 5 of 100 = 5%, under both caps.
        assertThat(MembershipReconciler.guardFor(true, 100, 100, 5).tripped()).isFalse();
    }
}
