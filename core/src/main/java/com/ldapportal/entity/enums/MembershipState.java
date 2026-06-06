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
 * </ul>
 */
public enum MembershipState {
    APPLIED,
    PENDING,
    FAILED
}
