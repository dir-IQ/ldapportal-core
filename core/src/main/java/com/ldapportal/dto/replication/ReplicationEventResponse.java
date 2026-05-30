// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.replication;

import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.entity.enums.ReplicationOperationType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * One row in the per-link event log. Payload is exposed so operators
 * can see what the worker is trying to apply when troubleshooting a
 * dead-lettered event.
 */
public record ReplicationEventResponse(
        UUID id,
        UUID linkId,
        String linkDisplayName,
        ReplicationEnqueueSource enqueueSource,
        ReplicationOperationType operation,
        String sourceDn,
        String targetDn,
        Map<String, Object> payload,
        ReplicationEventStatus status,
        int attempts,
        OffsetDateTime nextAttemptAt,
        String lastError,
        OffsetDateTime enqueuedAt,
        OffsetDateTime deliveredAt,
        // Source-side trace id (R2), surfaced from the payload so the
        // operator console can pivot to the originating audit rows.
        String correlationId) {

    public static ReplicationEventResponse from(ReplicationEvent e) {
        return new ReplicationEventResponse(
                e.getId(),
                e.getLink().getId(),
                e.getLink().getDisplayName(),
                e.getEnqueueSource(),
                e.getOperation(),
                e.getSourceDn(),
                e.getTargetDn(),
                e.getPayload(),
                e.getStatus(),
                e.getAttempts(),
                e.getNextAttemptAt(),
                e.getLastError(),
                e.getEnqueuedAt(),
                e.getDeliveredAt(),
                correlationId(e.getPayload()));
    }

    private static String correlationId(Map<String, Object> payload) {
        if (payload == null) return null;
        Object id = payload.get("correlationId");
        return id == null ? null : id.toString();
    }
}
