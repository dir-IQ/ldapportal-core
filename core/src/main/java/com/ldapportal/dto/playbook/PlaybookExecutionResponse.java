// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.playbook;

import com.ldapportal.entity.PlaybookExecution;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PlaybookExecutionResponse(
        UUID id,
        UUID playbookId,
        String playbookName,
        String targetDn,
        UUID executedBy,
        String status,
        String stepResults,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt) {

    public static PlaybookExecutionResponse from(PlaybookExecution e, String playbookName) {
        return new PlaybookExecutionResponse(
                e.getId(),
                e.getPlaybook().getId(),
                playbookName,
                e.getTargetDn(),
                e.getExecutedBy(),
                e.getStatus().name(),
                e.getStepResults(),
                e.getStartedAt(),
                e.getCompletedAt());
    }

    public static PlaybookExecutionResponse pending(String playbookName, UUID approvalId) {
        return new PlaybookExecutionResponse(
                approvalId, null, playbookName, null, null,
                "PENDING_APPROVAL", null, null, null);
    }
}
