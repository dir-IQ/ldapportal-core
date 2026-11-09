// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entity.enums;

/** The kinds of disagreement a sync set's content verify reports. */
public enum SyncDriftCategory {
    /** A member with no entry at its expected target DN. */
    MISSING,
    /** A target entry the set can't account for (no source member). */
    ORPHAN,
    /** Present on both sides, but the target's attributes differ from the projected source. */
    MISMATCH
}
