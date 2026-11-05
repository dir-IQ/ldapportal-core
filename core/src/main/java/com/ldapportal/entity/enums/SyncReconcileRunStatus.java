// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** Lifecycle of one recorded reconcile run of a sync set. */
public enum SyncReconcileRunStatus {
    /** Planning or applying; the heartbeat shows it's alive. */
    RUNNING,
    /** Reached the end of the run (which may still have had failed entries). */
    COMPLETE,
    /** Aborted by an unexpected error, or interrupted (a stale heartbeat). */
    FAILED
}
