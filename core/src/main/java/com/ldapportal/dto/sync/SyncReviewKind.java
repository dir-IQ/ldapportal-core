// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.ldapportal.entity.enums.MembershipState;

/**
 * Why a membership row is held for {@code REVIEW}, so the inventory can offer
 * the action that resolves it. The engine writes the reason text from the
 * prefixes below, and {@link #of} reads it back.
 */
public enum SyncReviewKind {
    /** The entry left scope; deleting its target entry waits for an operator. */
    PENDING_DELETE,
    /** Brownfield adoption found more than one target entry carrying the source anchor. */
    AMBIGUOUS_MATCH,
    /** An entry that doesn't carry the source anchor already sits at the placement DN. */
    PLACEMENT_CONFLICT,
    /** A reason this version doesn't recognize (e.g. a row written by an older release). */
    OTHER;

    /** Reason prefix of a held scope-exit (delete). */
    public static final String PENDING_DELETE_PREFIX = "scope-exit held for review";
    /** Reason prefix of an ambiguous anchor correlation. */
    public static final String AMBIGUOUS_MATCH_PREFIX = "ambiguous:";
    /** Reason prefix of an occupied placement DN. */
    public static final String PLACEMENT_CONFLICT_PREFIX = "unanchored entry already at placement DN";

    /** The kind of a row's review, or {@code null} when the row isn't in {@code REVIEW}. */
    public static SyncReviewKind of(MembershipState state, String reason) {
        if (state != MembershipState.REVIEW) {
            return null;
        }
        if (reason == null) {
            return OTHER;
        }
        if (reason.startsWith(PENDING_DELETE_PREFIX)) {
            return PENDING_DELETE;
        }
        if (reason.startsWith(AMBIGUOUS_MATCH_PREFIX)) {
            return AMBIGUOUS_MATCH;
        }
        if (reason.startsWith(PLACEMENT_CONFLICT_PREFIX)) {
            return PLACEMENT_CONFLICT;
        }
        return OTHER;
    }
}
