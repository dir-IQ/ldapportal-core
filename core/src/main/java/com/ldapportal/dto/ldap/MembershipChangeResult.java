// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;
import java.util.UUID;

/**
 * Per-change outcome of a {@link MembershipChangeRequest} batch.
 *
 * <p>The batch is best-effort: each change is attempted independently and
 * classified, so one failure never aborts the rest. The count fields are a
 * convenience summary of {@link #items()}.</p>
 */
public record MembershipChangeResult(
        int applied,
        int queued,
        int refused,
        int blocked,
        int errored,
        List<Item> items) {

    public enum Status {
        /** Change written to the directory. */
        APPLIED,
        /** Add fell under a profile requiring approval; queued, not yet applied. */
        QUEUED_FOR_APPROVAL,
        /** A provisioning interceptor refused the change (e.g. ISVA non-secGroup target). */
        REFUSED,
        /** A Separation-of-Duties BLOCK policy rejected the add. */
        BLOCKED,
        /** Any other failure; see {@link Item#message()}. */
        ERROR
    }

    public record Item(
            String groupDn,
            MembershipChangeRequest.Op op,
            Status status,
            /** Set only when {@code status == QUEUED_FOR_APPROVAL}. */
            UUID approvalId,
            /** Human-readable reason; set for REFUSED / BLOCKED / ERROR. */
            String message) {

        public static Item applied(MembershipChangeRequest.Change c) {
            return new Item(c.groupDn(), c.op(), Status.APPLIED, null, null);
        }

        public static Item queued(MembershipChangeRequest.Change c, UUID approvalId) {
            return new Item(c.groupDn(), c.op(), Status.QUEUED_FOR_APPROVAL, approvalId, null);
        }

        public static Item refused(MembershipChangeRequest.Change c, String message) {
            return new Item(c.groupDn(), c.op(), Status.REFUSED, null, message);
        }

        public static Item blocked(MembershipChangeRequest.Change c, String message) {
            return new Item(c.groupDn(), c.op(), Status.BLOCKED, null, message);
        }

        public static Item errored(MembershipChangeRequest.Change c, String message) {
            return new Item(c.groupDn(), c.op(), Status.ERROR, null, message);
        }
    }

    /** Tallies the per-change statuses into the summary counts. */
    public static MembershipChangeResult of(List<Item> items) {
        int applied = 0, queued = 0, refused = 0, blocked = 0, errored = 0;
        for (Item it : items) {
            switch (it.status()) {
                case APPLIED -> applied++;
                case QUEUED_FOR_APPROVAL -> queued++;
                case REFUSED -> refused++;
                case BLOCKED -> blocked++;
                case ERROR -> errored++;
            }
        }
        return new MembershipChangeResult(applied, queued, refused, blocked, errored, items);
    }
}
