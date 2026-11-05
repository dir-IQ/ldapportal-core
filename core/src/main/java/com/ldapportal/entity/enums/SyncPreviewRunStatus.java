// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** Lifecycle of a background reconcile-preview run. */
public enum SyncPreviewRunStatus {
    /** Planning and dry-running; the heartbeat shows it's alive. */
    RUNNING,
    /** Finished; its entries can be paged. */
    COMPLETE,
    /** Couldn't run (see the error), or was interrupted. */
    FAILED
}
