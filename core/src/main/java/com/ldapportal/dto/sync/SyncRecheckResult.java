// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

/**
 * Outcome of asking a sync set to re-check its target.
 *
 * @param entries         applied entries whose last-written state was forgotten,
 *                        so the next reconcile re-reads and re-applies each one
 * @param reconcileQueued true when the set and its link are enabled, so the
 *                        requested reconcile runs on the scheduler's next tick;
 *                        false when either is disabled, so the re-check waits
 *                        until both are enabled
 */
public record SyncRecheckResult(int entries, boolean reconcileQueued) {
}
