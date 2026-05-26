// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.approval;

import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.enums.ApprovalRequestType;
import com.ldapportal.entity.enums.ApprovalStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PendingApprovalResponse(
        UUID id,
        UUID directoryId,
        UUID profileId,
        UUID requestedBy,
        String requesterUsername,
        ApprovalStatus status,
        ApprovalRequestType requestType,
        String payload,
        String rejectReason,
        String provisionError,
        UUID reviewedBy,
        String reviewerUsername,
        OffsetDateTime createdAt,
        OffsetDateTime reviewedAt) {

    public static PendingApprovalResponse from(PendingApproval pa,
                                                String requesterUsername,
                                                String reviewerUsername) {
        return new PendingApprovalResponse(
                pa.getId(),
                pa.getDirectoryId(),
                pa.getProfileId(),
                pa.getRequestedBy(),
                requesterUsername,
                pa.getStatus(),
                pa.getRequestType(),
                pa.getPayload(),
                pa.getRejectReason(),
                pa.getProvisionError(),
                pa.getReviewedBy(),
                reviewerUsername,
                pa.getCreatedAt(),
                pa.getReviewedAt());
    }
}
