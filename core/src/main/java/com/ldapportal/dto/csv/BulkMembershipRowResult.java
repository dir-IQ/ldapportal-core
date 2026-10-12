// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.csv;

import com.ldapportal.dto.ldap.MembershipChangeRequest;

import java.util.UUID;

/** Outcome of one user-group pair after a bulk membership commit. */
public record BulkMembershipRowResult(
        int rowNumber,
        String user,
        String userDn,
        String group,
        String groupDn,
        MembershipChangeRequest.Op op,
        Status status,
        /** Set only when {@code status == QUEUED_FOR_APPROVAL}. */
        UUID approvalId,
        /** Reason for SKIPPED / REFUSED / BLOCKED / ERROR. */
        String message) {

    public enum Status {
        /** Written to the directory. */
        APPLIED,
        /** ADD queued for approval; not yet applied. */
        QUEUED_FOR_APPROVAL,
        /** Nothing to do: already a member, not a member, or a duplicate row. */
        SKIPPED,
        /** A provisioning interceptor refused the change. */
        REFUSED,
        /** A Separation-of-Duties BLOCK policy rejected the add. */
        BLOCKED,
        /** Couldn't be resolved, or the write failed. */
        ERROR
    }
}
