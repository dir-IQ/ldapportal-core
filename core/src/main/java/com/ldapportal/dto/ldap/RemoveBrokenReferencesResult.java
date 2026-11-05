// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;

/**
 * What removing broken member references did, one outcome per reference.
 *
 * @param removed  references removed from their groups
 * @param outcomes every reference's outcome, in request order
 */
public record RemoveBrokenReferencesResult(int removed, List<Outcome> outcomes) {

    public enum Status {
        /** Removed from the group. */
        REMOVED,
        /** Kept: an entry exists at the referenced DN now, so the reference isn't broken. */
        KEPT_ENTRY_EXISTS,
        /** Nothing to do: the group no longer holds the reference (or the group is gone). */
        ALREADY_GONE,
        /** The group's modify failed; {@code message} says why. The group is unchanged. */
        FAILED
    }

    public record Outcome(String groupDn, String attribute, String value, Status status, String message) {
    }
}
