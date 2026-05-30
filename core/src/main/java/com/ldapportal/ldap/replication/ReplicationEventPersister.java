// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.repository.ReplicationEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Sibling bean that owns the {@code @Transactional(REQUIRES_NEW)}
 * boundary for enqueueing replication events. Lives on a separate
 * bean — not as a private method on {@link ReplicationEnqueuer} —
 * because Spring's transactional proxy only applies to cross-bean
 * calls.
 *
 * <p>This indirection lets {@link ReplicationEnqueuer} short-circuit
 * the common "no replication links for this source directory" case
 * <em>without</em> entering a new transaction. Every successful LDAP
 * write in the application calls the enqueuer; most directories have
 * no replication links, so paying the BEGIN/COMMIT cost on those
 * writes was a hot-path regression introduced by P0 — fixing it
 * required moving the {@code @Transactional} off the entry method.
 */
@Component
@RequiredArgsConstructor
public class ReplicationEventPersister {

    private final ReplicationEventRepository eventRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAll(List<ReplicationEvent> events) {
        for (ReplicationEvent e : events) {
            eventRepo.save(e);
        }
    }
}
