// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.repository.ReplicationEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Sibling bean that owns the {@code @Transactional(REQUIRES_NEW)}
 * methods the {@link ReplicationWorker} needs to call. Lives on a
 * separate bean — not as private methods on the worker — because
 * Spring's transactional proxy only applies to cross-bean calls.
 * Intra-class self-invocation (worker calling its own
 * {@code @Transactional} method) silently bypasses the proxy and
 * runs without any tx, which the previous shape did and which
 * broke the entire delivery path.
 *
 * <p>Each method commits in its own short-lived transaction so the
 * worker thread isn't holding a DB connection across the LDAP
 * delivery round-trip, and so other workers (or this worker's next
 * tick) see status flips immediately.
 */
@Component
@RequiredArgsConstructor
public class ReplicationEventTxOps {

    private final ReplicationEventRepository eventRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int tryClaim(UUID eventId) {
        return eventRepo.tryClaim(eventId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDelivered(UUID eventId) {
        eventRepo.markDelivered(eventId, OffsetDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailure(UUID eventId,
                             ReplicationEventStatus status,
                             int attempts,
                             OffsetDateTime nextAttemptAt,
                             String lastError) {
        eventRepo.markFailure(eventId, status, attempts, nextAttemptAt, lastError);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int resetStaleInFlight(OffsetDateTime threshold) {
        return eventRepo.resetStaleInFlight(threshold);
    }
}
