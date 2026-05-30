// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.entity.enums.ReplicationOperationType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable projection of a {@link ReplicationEvent} for the worker
 * and delivery paths — see {@link ReplicationLinkSnapshot} for the
 * design rationale shared by both snapshots. Carries a fully-materialised
 * {@link ReplicationLinkSnapshot} so the delivery code has every field
 * it needs (including attribute-mapping rules for the auto-create path)
 * with zero JPA proxy involvement.
 */
public record ReplicationEventSnapshot(
        UUID id,
        ReplicationLinkSnapshot link,
        ReplicationEnqueueSource enqueueSource,
        ReplicationOperationType operation,
        String sourceDn,
        String targetDn,
        Map<String, Object> payload,
        int attempts,
        OffsetDateTime enqueuedAt) {

    /**
     * Materialise an event entity into a snapshot. Same session-lifecycle
     * contract as {@link ReplicationLinkSnapshot#from} — must run while
     * the entity's session is open, and the repository query that returned
     * the event must {@code JOIN FETCH} {@code link.attributeMappings},
     * {@code link.sourceDirectory}, {@code link.targetDirectory}.
     */
    public static ReplicationEventSnapshot from(ReplicationEvent e) {
        return new ReplicationEventSnapshot(
                e.getId(),
                ReplicationLinkSnapshot.from(e.getLink()),
                e.getEnqueueSource(),
                e.getOperation(),
                e.getSourceDn(),
                e.getTargetDn(),
                e.getPayload(),
                e.getAttempts(),
                e.getEnqueuedAt());
    }
}
