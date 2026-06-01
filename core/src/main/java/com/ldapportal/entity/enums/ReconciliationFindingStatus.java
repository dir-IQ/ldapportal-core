// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * Lifecycle of a persisted reconciliation finding (R-P2).
 *
 * <ul>
 *   <li>{@link #PROPOSED}     — detected and awaiting operator review.</li>
 *   <li>{@link #AUTO_APPLIED} — the run auto-applied it (AUTO_CORRECT mode,
 *       or AUTO delete-action); a corrective event was enqueued.</li>
 *   <li>{@link #APPLIED}      — an operator selected it for apply; a
 *       corrective event was enqueued.</li>
 *   <li>{@link #DISMISSED}    — an operator chose not to act on it.</li>
 *   <li>{@link #SUPERSEDED}   — reserved: a later run replaced it.</li>
 * </ul>
 */
public enum ReconciliationFindingStatus {
    PROPOSED,
    AUTO_APPLIED,
    APPLIED,
    DISMISSED,
    SUPERSEDED
}
