// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

/**
 * What a reconcile would do to one entry, as predicted by a dry run of the
 * engine's own decision path. A live recompute reports what it did in the same
 * terms, so a reconcile's recorded counts compare directly with its preview.
 */
public enum SyncPreviewAction {
    /** Create the entry on the target. */
    ADD,
    /** Change attributes of an existing target entry. */
    MODIFY,
    /** Rename/move the target entry (placement changed), possibly with attribute changes. */
    MOVE,
    /** Delete the target entry (left the set; hold for review NONE). */
    DELETE,
    /** Held for an operator: hold for review (a delete, or under ALL any write), the delete guard, or a brownfield ambiguity. */
    REVIEW,
    /** The engine would apply it, but the target already matches: no target write. */
    NO_CHANGE,
    /** Drop a stale index row whose target entry another identity now owns; the target is kept. */
    FORGET,
    /** Already applied with the same content, or already held for review: nothing happens. */
    UNCHANGED,
    /**
     * The outcome couldn't be predicted: reading the target or projecting the
     * entry failed. The reason says why; a reconcile would likely fail the same way.
     */
    ERROR
}
