// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.entity.enums.ReplicationOperationType;

import java.util.Map;
import java.util.UUID;

/**
 * The value the {@link ReplicationEnqueuer} hands the
 * {@link ReplicationEventPersister} for each fan-out target. Carries the
 * link's id (not the entity) so the persister can resolve the FK inside
 * its own transaction via {@code em.getReference(ReplicationLink.class, id)}
 * — no entity reference crosses the non-transactional boundary into
 * the persister's tx, and no LAZY collection access is possible from
 * here.
 *
 * <p>{@code sourceChangeNumber} is the source {@code changeNumber} for
 * {@code SOURCE_CHANGELOG} events (the exactly-once dedup key); {@code null}
 * for the live {@code APP_INTERCEPT} path.
 */
public record PendingReplicationEvent(
        UUID linkId,
        ReplicationEnqueueSource enqueueSource,
        ReplicationOperationType operation,
        String sourceDn,
        String targetDn,
        Map<String, Object> payload,
        Long sourceChangeNumber) {

    /** Live-capture convenience: no source change number (APP_INTERCEPT). */
    public PendingReplicationEvent(UUID linkId, ReplicationEnqueueSource enqueueSource,
                                   ReplicationOperationType operation, String sourceDn,
                                   String targetDn, Map<String, Object> payload) {
        this(linkId, enqueueSource, operation, sourceDn, targetDn, payload, null);
    }
}
