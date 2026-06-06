// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

/**
 * Translates the persisted opaque cursor token to/from the position a given
 * changelog family understands. The token is the canonical, format-agnostic
 * cursor stored on the link; this is the seam where each feed interprets it.
 *
 * <ul>
 *   <li><b>DSEE numeric family</b> (and any monotonic-changeNumber feed): the
 *       token is the decimal changeNumber as text — {@link #toChangeNumber} /
 *       {@link #fromChangeNumber} convert it.</li>
 *   <li><b>Cookie-based feeds</b> (AD DirSync, syncrepl, Entra delta): their poll
 *       loop reads the token verbatim as its cookie / delta link — no numeric
 *       interpretation. (Those poll loops land with their adapters.)</li>
 * </ul>
 */
public final class SyncChangelogCursor {

    private SyncChangelogCursor() {
    }

    /** The DSEE {@code afterChangeNumber} from the opaque token, or null (from the start). */
    public static Long toChangeNumber(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(token.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static String fromChangeNumber(long changeNumber) {
        return Long.toString(changeNumber);
    }
}
