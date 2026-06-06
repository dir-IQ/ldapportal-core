// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * Lifecycle state of a {@link com.ldapportal.entity.Membership} row — the
 * outcome of the last recompute/apply for that identity.
 *
 * <ul>
 *   <li>{@code APPLIED} — the target reflects the projected desired state.</li>
 *   <li>{@code PENDING} — a transition is queued but not yet applied.</li>
 *   <li>{@code FAILED} — the last apply failed; this identity is dead-lettered
 *       and retried, without blocking any other identity.</li>
 *   <li>{@code REVIEW} — brownfield quarantine: the target correlation is
 *       ambiguous (multiple sourceAnchor matches, or an unanchored entry already
 *       sits at the placement DN), so the engine holds for an operator decision
 *       rather than risk overwriting/deleting the wrong target entry.</li>
 * </ul>
 */
public enum MembershipState {
    APPLIED,
    PENDING,
    FAILED,
    REVIEW
}
