// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import com.ldapportal.dto.ldap.MembershipChangeRequest;

/**
 * One user-group pair from a bulk membership CSV, resolved and classified
 * without writing anything. A CSV row naming several groups expands into one
 * preview row per group, all sharing the row's {@code rowNumber}.
 */
public record BulkMembershipPreviewRow(
        int rowNumber,
        /** The user value as written in the CSV (uid or DN). */
        String user,
        /** Resolved user DN, or {@code null} when the user couldn't be resolved. */
        String userDn,
        /** The group value as written in the CSV (cn or DN). */
        String group,
        /** Resolved group DN, or {@code null} when the group couldn't be resolved. */
        String groupDn,
        MembershipChangeRequest.Op op,
        Disposition disposition,
        /** Why the pair isn't actionable; {@code null} for WILL_ADD / WILL_REMOVE. */
        String note) {

    public enum Disposition {
        /** User and group resolved, user not yet a member — will be added. */
        WILL_ADD,
        /** User and group resolved, user is a member — will be removed. */
        WILL_REMOVE,
        /** ADD for a user who is already a member — skipped. */
        ALREADY_MEMBER,
        /** REMOVE for a user who isn't a member — skipped. */
        NOT_A_MEMBER,
        /** The same user-group-op appears earlier in the file — skipped. */
        DUPLICATE,
        /** No user with this uid / at this DN. */
        USER_NOT_FOUND,
        /** No group with this cn / at this DN. */
        GROUP_NOT_FOUND,
        /** The uid or cn matched more than one entry (never guessed). */
        AMBIGUOUS,
        /** The user or group is outside the caller's authorized scope. */
        OUT_OF_SCOPE,
        /** Blank value, unknown op, conflicting ADD+REMOVE, or a target that can't take this member. */
        INVALID;

        public boolean actionable() {
            return this == WILL_ADD || this == WILL_REMOVE;
        }
    }
}
