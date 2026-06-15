// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * What the engine does when a tracked identity leaves a sync set's membership
 * (deleted at source, scope-exit, or applicability-exit).
 *
 * <ul>
 *   <li>{@code DELETE} — remove the target entry.</li>
 *   <li>{@code REVIEW} — hold for operator review rather than auto-delete
 *       (quarantine wiring lands with the brownfield/UI phase).</li>
 * </ul>
 */
public enum SyncDeletePolicy {
    DELETE,
    REVIEW
}
