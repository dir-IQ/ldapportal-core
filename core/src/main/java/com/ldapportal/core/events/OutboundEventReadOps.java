// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
import com.ldapportal.core.events.snapshot.EventSubscriptionSnapshot;
import com.ldapportal.core.events.snapshot.OutboxEntrySnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-side transactional boundary for the outbound-event dispatch
 * pipeline. Owns the {@code @Transactional(readOnly=true)} that
 * materialises JPA entities into immutable
 * {@link OutboxEntrySnapshot} / {@link EventSubscriptionSnapshot}
 * records before returning them to the
 * {@link OutboundDispatcherScheduler} and the
 * {@code OutboundChannel} implementations.
 *
 * <p>Direct sibling to
 * {@code com.ldapportal.ldap.replication.ReplicationReadOps} — same
 * architectural role, same rationale. The dispatcher and channels
 * run outside any caller transaction (the dispatcher is
 * {@code @Scheduled}; the channels do HTTP I/O that mustn't be held
 * in a DB tx). Passing JPA entities to that path couples its
 * correctness to invisible session-lifecycle details and turns
 * accidental access to a LAZY field into a silent
 * {@code transientFailure} that re-enters the retry loop forever.
 * Snapshots push the JPA boundary one method further in: the
 * entity exists only inside this bean's methods, and nothing the
 * downstream code touches can trigger a lazy load.
 *
 * <p>The {@code @Transactional} cost here is the same as before —
 * Spring Data already opens a short tx per repository call when
 * invoked from a non-transactional context. Moving that tx
 * boundary onto this service keeps the entity attached long enough
 * for the snapshot factory to materialise everything it needs.
 */
@Component
@RequiredArgsConstructor
public class OutboundEventReadOps {

    private final OutboxEntryRepository       outboxRepository;
    private final EventSubscriptionRepository subscriptionRepository;

    @Transactional(readOnly = true)
    public Optional<OutboxEntrySnapshot> outboxSnapshot(UUID id) {
        return outboxRepository.findById(id).map(OutboxEntrySnapshot::from);
    }

    @Transactional(readOnly = true)
    public Optional<EventSubscriptionSnapshot> subscriptionSnapshot(UUID id) {
        return subscriptionRepository.findById(id).map(EventSubscriptionSnapshot::from);
    }
}
