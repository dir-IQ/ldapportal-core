// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.repository.ReplicationEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
 * writes was a hot-path regression fixed by moving the
 * {@code @Transactional} off the entry method.
 *
 * <p>The input type is {@link PendingReplicationEvent} (a plain
 * record) rather than {@code ReplicationEvent} (the JPA entity). The
 * enqueuer never constructs an entity; the persister builds it
 * inside its own tx using {@link EntityManager#getReference} so the
 * link's FK is resolved without loading the link from the DB. This
 * is the architectural fix for the lazy-load bug class: no JPA
 * entity reference ever crosses out of the non-transactional
 * enqueuer's call frame.
 */
@Component
@RequiredArgsConstructor
public class ReplicationEventPersister {

    private final ReplicationEventRepository eventRepo;

    @PersistenceContext
    private EntityManager em;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAll(List<PendingReplicationEvent> pending) {
        for (PendingReplicationEvent p : pending) {
            ReplicationEvent e = new ReplicationEvent();
            // getReference returns a Hibernate proxy without issuing
            // a SELECT — we already know the link id is valid because
            // the snapshot was just read for it. The proxy is enough
            // for JPA to set the link_id FK column on insert.
            e.setLink(em.getReference(ReplicationLink.class, p.linkId()));
            e.setEnqueueSource(p.enqueueSource());
            e.setOperation(p.operation());
            e.setSourceDn(p.sourceDn());
            e.setTargetDn(p.targetDn());
            e.setStatus(ReplicationEventStatus.PENDING);
            e.setPayload(p.payload());
            eventRepo.save(e);
        }
    }
}
