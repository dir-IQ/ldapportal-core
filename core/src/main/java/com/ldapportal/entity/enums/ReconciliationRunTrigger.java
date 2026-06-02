// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** What initiated a reconciliation run. */
public enum ReconciliationRunTrigger {
    /** The periodic scheduler fired because the link was due. */
    SCHEDULED,
    /** An operator pressed "Reconcile now". */
    MANUAL
}
