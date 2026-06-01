// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * How a replication link resolves reconciliation findings for the
 * missing-entry and attribute-drift discrepancy classes.
 *
 * <ul>
 *   <li>{@link #REVIEW} — findings are persisted as proposals for an
 *       operator to review and selectively apply. The safe default.</li>
 *   <li>{@link #AUTO_CORRECT} — findings are applied automatically by
 *       enqueueing corrective {@code replication_events}.</li>
 * </ul>
 *
 * <p>Deletion of target entries with no source counterpart is governed
 * separately by {@link ReconcileDeleteAction}, not by this mode.
 *
 * <p>See {@code docs/plans/2026-05-31-replication-reconciliation-design.md}.
 */
public enum ReconcileMode {
    AUTO_CORRECT,
    REVIEW
}
