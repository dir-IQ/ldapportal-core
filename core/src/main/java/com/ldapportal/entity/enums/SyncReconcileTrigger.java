// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** What started a reconcile run. */
public enum SyncReconcileTrigger {
    /** The scheduler: the set's cadence elapsed, or the changelog poller asked for one. */
    SCHEDULED,
    /** An operator's "Reconcile now". */
    MANUAL,
    /**
     * Not a reconcile: the changes the recompute worker applied in one pass
     * between reconciles (changelog records, in-app writes, closure fan-out).
     */
    INCREMENTAL;

    /** The triggers of full reconciles, as opposed to incremental passes. */
    public static final java.util.Set<SyncReconcileTrigger> RECONCILES =
            java.util.EnumSet.of(SCHEDULED, MANUAL);
}
