// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * Classes of discrepancy a reconciliation run can detect between a
 * replication link's source and target subtrees.
 *
 * <ul>
 *   <li>{@link #MISSING_IN_TARGET} — a source entry has no target
 *       counterpart → corrective ADD.</li>
 *   <li>{@link #ATTRIBUTE_DRIFT}   — both exist but managed attribute
 *       values differ → corrective MODIFY.</li>
 *   <li>{@link #EXTRA_IN_TARGET}   — a target entry (in scope) has no
 *       source counterpart → corrective DELETE, governed by
 *       {@link ReconcileDeleteAction}.</li>
 * </ul>
 */
public enum ReconciliationFindingType {
    MISSING_IN_TARGET,
    ATTRIBUTE_DRIFT,
    EXTRA_IN_TARGET
}
