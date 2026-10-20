// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** What started a reconcile run. */
public enum SyncReconcileTrigger {
    /** The scheduler: the set's cadence elapsed, or the changelog poller asked for one. */
    SCHEDULED,
    /** An operator's "Reconcile now". */
    MANUAL
}
