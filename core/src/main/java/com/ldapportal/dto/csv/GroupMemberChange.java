// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import com.ldapportal.dto.ldap.MembershipChangeRequest;

import java.util.UUID;

/**
 * One member change a group-import row asks for: the CSV value, the entry it
 * resolved to, and what happens (preview) or happened (import) to it.
 *
 * @param member     the value as written in the CSV ({@code members} column)
 * @param memberDn   the entry it resolved to, or {@code null} when unresolved
 * @param op         ADD or REMOVE (from the row's {@code member-operation})
 * @param outcome    planned outcome in a preview, actual outcome after import
 * @param message    detail for the outcome, e.g. why it was refused
 * @param approvalId the pending approval when the add was queued
 */
public record GroupMemberChange(
        String member,
        String memberDn,
        MembershipChangeRequest.Op op,
        Outcome outcome,
        String message,
        UUID approvalId) {

    public enum Outcome {
        // Planned (preview only)
        WILL_ADD,
        WILL_REMOVE,
        // Written (import only)
        APPLIED,
        QUEUED_FOR_APPROVAL,
        REFUSED,
        BLOCKED,
        ERROR,
        // Nothing to write (preview and import)
        ALREADY_MEMBER,
        NOT_A_MEMBER,
        DUPLICATE,
        // Can't be written (preview and import)
        MEMBER_NOT_FOUND,
        AMBIGUOUS,
        OUT_OF_SCOPE,
        INVALID;

        /** The change will be (or was attempted to be) written. */
        public boolean planned() {
            return this == WILL_ADD || this == WILL_REMOVE;
        }

        /** Nothing needed doing. */
        public boolean noOp() {
            return this == ALREADY_MEMBER || this == NOT_A_MEMBER || this == DUPLICATE;
        }

        /** The change wasn't (or won't be) made because of a problem. */
        public boolean failed() {
            return this == REFUSED || this == BLOCKED || this == ERROR
                    || this == MEMBER_NOT_FOUND || this == AMBIGUOUS
                    || this == OUT_OF_SCOPE || this == INVALID;
        }
    }

    public GroupMemberChange withOutcome(Outcome newOutcome, String newMessage, UUID newApprovalId) {
        return new GroupMemberChange(member, memberDn, op, newOutcome, newMessage, newApprovalId);
    }
}
