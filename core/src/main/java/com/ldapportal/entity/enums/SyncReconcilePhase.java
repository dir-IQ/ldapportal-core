// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** Where a running reconcile has got to. */
public enum SyncReconcilePhase {
    /** Reading the source scope and planning the run. */
    SCANNING,
    /** Recomputing each source entry the plan found. */
    APPLYING,
    /** Recomputing managed entries the source scan didn't find (out-of-scope or deleted). */
    SWEEPING,
    /** Finding the entries that refer to ones the run changed, and queuing them for recompute. */
    REFERENCES
}
