// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.replication;

import com.ldapportal.entity.ReconciliationRun;
import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReconciliationRunStatus;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Read-only view of a {@link ReconciliationRun} for the runs-history API. */
public record ReconciliationRunResponse(
        UUID id,
        UUID linkId,
        ReconciliationRunTrigger trigger,
        ReconcileMode mode,
        ReconciliationRunStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Integer sourceEntryCount,
        Integer targetEntryCount,
        int missingCount,
        int driftCount,
        int extraCount,
        int suppressedCount,
        int appliedCount,
        String error) {

    public static ReconciliationRunResponse from(ReconciliationRun r) {
        return new ReconciliationRunResponse(
                r.getId(),
                r.getLink().getId(),
                r.getTrigger(),
                r.getMode(),
                r.getStatus(),
                r.getStartedAt(),
                r.getFinishedAt(),
                r.getSourceEntryCount(),
                r.getTargetEntryCount(),
                r.getMissingCount(),
                r.getDriftCount(),
                r.getExtraCount(),
                r.getSuppressedCount(),
                r.getAppliedCount(),
                r.getError());
    }
}
