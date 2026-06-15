// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.audit;

import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.AuditSource;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(
        UUID id,
        AuditSource source,
        UUID actorId,
        String actorType,
        String actorUsername,
        UUID directoryId,
        String directoryName,
        AuditAction action,
        String targetDn,
        Map<String, Object> detail,
        String changelogChangeNumber,
        OffsetDateTime occurredAt,
        OffsetDateTime recordedAt,
        // Trace id (R2) for pivoting to related rows of the same operation.
        UUID correlationId
) {
    public static AuditEventResponse from(AuditEvent e) {
        return new AuditEventResponse(
                e.getId(),
                e.getSource(),
                e.getActorId(),
                e.getActorType(),
                e.getActorUsername(),
                e.getDirectoryId(),
                e.getDirectoryName(),
                e.getAction(),
                e.getTargetDn(),
                e.getDetail(),
                e.getChangelogChangeNumber(),
                e.getOccurredAt(),
                e.getRecordedAt(),
                e.getCorrelationId()
        );
    }
}
