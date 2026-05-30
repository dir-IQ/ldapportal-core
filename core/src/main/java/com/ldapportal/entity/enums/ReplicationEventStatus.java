// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * Lifecycle states for a single replication event.
 *
 * <ul>
 *   <li>{@link #PENDING}        — enqueued, awaiting first delivery attempt.</li>
 *   <li>{@link #IN_FLIGHT}      — worker has claimed the row and is
 *       attempting delivery against the target. Transient.</li>
 *   <li>{@link #DELIVERED}      — applied to the target successfully.
 *       Terminal.</li>
 *   <li>{@link #FAILED}         — at least one delivery attempt has failed
 *       but retries remain. The worker will pick the row up again at
 *       {@code next_attempt_at}.</li>
 *   <li>{@link #DEAD_LETTERED}  — retry budget exhausted. The operator
 *       must explicitly retry, skip, or acknowledge. Terminal until
 *       operator action.</li>
 *   <li>{@link #SKIPPED}        — operator chose to discard without
 *       applying. Terminal.</li>
 *   <li>{@link #ACKNOWLEDGED}   — operator marked a dead-lettered row as
 *       seen-and-dismissed. Terminal; differs from SKIPPED in that the
 *       operator confirmed they understood the failure rather than
 *       intentionally dropping the change.</li>
 * </ul>
 */
public enum ReplicationEventStatus {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    FAILED,
    DEAD_LETTERED,
    SKIPPED,
    ACKNOWLEDGED
}
