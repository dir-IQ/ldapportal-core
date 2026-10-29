// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/**
 * Which of a sync set's target writes wait for an operator's approval instead
 * of being applied as soon as the engine decides them.
 *
 * <ul>
 *   <li>{@code NONE} — every write is applied, deletes included.</li>
 *   <li>{@code DELETES} — the target entry of an entry that leaves the set
 *       (deleted on the source, moved out of scope, or no longer matching the
 *       filter) is held for review; adds, updates and moves are applied.</li>
 *   <li>{@code ALL} — every write is held: adds, attribute updates and moves as
 *       well as deletes. An approved update applies the source's state at
 *       approval time, not the state it was held with.</li>
 * </ul>
 */
public enum SyncHoldForReview {
    NONE,
    DELETES,
    ALL;

    /** Whether an entry leaving the set has its target delete held. */
    public boolean holdsDeletes() {
        return this != NONE;
    }

    /** Whether adds, attribute updates and moves are held. */
    public boolean holdsUpdates() {
        return this == ALL;
    }
}
