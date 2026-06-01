// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.replication;

import com.ldapportal.entity.ReconciliationFinding;
import com.ldapportal.entity.enums.ReconciliationFindingStatus;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReplicationOperationType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Read-only view of a reconciliation finding for the review UI. */
public record ReconciliationFindingResponse(
        UUID id,
        UUID runId,
        UUID linkId,
        ReconciliationFindingType findingType,
        ReplicationOperationType suggestedOp,
        String sourceDn,
        String targetDn,
        Map<String, Object> detail,
        ReconciliationFindingStatus status,
        UUID eventId,
        OffsetDateTime createdAt,
        OffsetDateTime resolvedAt) {

    public static ReconciliationFindingResponse from(ReconciliationFinding f) {
        return new ReconciliationFindingResponse(
                f.getId(),
                f.getRun().getId(),
                f.getLink().getId(),
                f.getFindingType(),
                f.getSuggestedOp(),
                f.getSourceDn(),
                f.getTargetDn(),
                f.getDetail(),
                f.getStatus(),
                f.getEventId(),
                f.getCreatedAt(),
                f.getResolvedAt());
    }
}
