// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side transactional boundary for the replication subsystem.
 * Owns the {@code @Transactional(readOnly = true)} that materialises
 * JPA entities into immutable {@link ReplicationLinkSnapshot} /
 * {@link ReplicationEventSnapshot} records before returning them to
 * the non-transactional consumers (enqueuer, worker, delivery).
 *
 * <p>Why this exists (the architectural fix for the lazy-load bug class):
 * the consumers below run outside any caller transaction, on purpose —
 * the enqueuer is on the LDAP-write hot path and must short-circuit
 * cheaply, and the worker drains the queue from a {@code @Scheduled}
 * method whose semantics are explicitly non-transactional. Letting
 * JPA entities cross out of a tx into those consumers couples their
 * correctness to invisible session-lifecycle details; two confirmed
 * silent-data-loss bugs landed because of that coupling. Snapshots
 * push the JPA boundary one method further in: the entity exists only
 * inside this bean's methods, and nothing the consumers touch can
 * trigger a lazy load. The bug class is structurally absent rather
 * than absent by careful coding.
 *
 * <p>The {@code @Transactional} cost is the same as before — Spring
 * Data already opens a short tx per repository call when invoked from
 * a non-transactional context. Moving that tx boundary onto this
 * service keeps the entity attached long enough for the snapshot
 * factory to materialise everything it needs.
 */
@Component
@RequiredArgsConstructor
public class ReplicationReadOps {

    private final ReplicationLinkRepository  linkRepo;
    private final ReplicationEventRepository eventRepo;

    /**
     * All enabled link snapshots whose source is the given directory.
     * The repository query {@code LEFT JOIN FETCH}es every association
     * the snapshot reads, so the {@code map} call below performs no
     * additional SQL and the returned records are safe to use after
     * this method returns.
     */
    @Transactional(readOnly = true)
    public List<ReplicationLinkSnapshot> snapshotsForSource(UUID sourceDirectoryId) {
        return linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(sourceDirectoryId)
                .stream()
                .map(ReplicationLinkSnapshot::from)
                .toList();
    }

    /**
     * The earliest claimable event for a given link, materialised as a
     * snapshot. The repository's {@code findEarliestClaimableForLink}
     * query {@code JOIN FETCH}es link + both directories + attribute
     * mappings, so the snapshot factory runs without further SQL.
     */
    @Transactional(readOnly = true)
    public Optional<ReplicationEventSnapshot> earliestClaimableSnapshot(
            UUID linkId, OffsetDateTime now) {
        return eventRepo.findEarliestClaimableForLink(linkId, now)
                .map(ReplicationEventSnapshot::from);
    }
}
