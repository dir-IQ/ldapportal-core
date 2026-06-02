// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * How reconciliation handles an {@code EXTRA_IN_TARGET} discrepancy — a
 * target entry, within the link's scope, that has no source counterpart.
 * Chosen by the operator independently of {@link ReconcileMode} because
 * deletion is the one irreversible corrective action.
 *
 * <ul>
 *   <li>{@link #IGNORE} — never flag extras; the target may legitimately
 *       hold entries the source doesn't.</li>
 *   <li>{@link #REVIEW} — surface extras as proposals for operator review,
 *       even when {@link ReconcileMode#AUTO_CORRECT} auto-applies
 *       adds/drift. The default.</li>
 *   <li>{@link #AUTO} — enqueue the corrective DELETE automatically.</li>
 * </ul>
 *
 * <p>See {@code docs/plans/2026-05-31-replication-reconciliation-design.md}.
 */
public enum ReconcileDeleteAction {
    IGNORE,
    REVIEW,
    AUTO
}
