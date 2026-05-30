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
 */
public record PendingReplicationEvent(
        UUID linkId,
        ReplicationEnqueueSource enqueueSource,
        ReplicationOperationType operation,
        String sourceDn,
        String targetDn,
        Map<String, Object> payload) {}
