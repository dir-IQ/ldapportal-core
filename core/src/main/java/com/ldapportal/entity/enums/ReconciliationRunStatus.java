// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * Lifecycle of a single reconciliation run.
 *
 * <ul>
 *   <li>{@link #RUNNING}   — claimed and executing. A unique partial
 *       index allows at most one RUNNING run per link (single-flight).</li>
 *   <li>{@link #COMPLETED} — the compare finished; counts are populated.</li>
 *   <li>{@link #FAILED}    — the compare aborted (directory unreachable,
 *       safety cap exceeded, etc.); {@code error} carries the reason.</li>
 *   <li>{@link #CANCELLED} — reserved for operator-cancelled runs.</li>
 * </ul>
 */
public enum ReconciliationRunStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
