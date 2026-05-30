// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.repository.ReplicationLinkRepository;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Takes a {@link CapturedWrite} from the {@code ReplicatingLDAPInterface}
 * wrapper, finds every enabled {@link ReplicationLink} whose source is
 * the given directory, applies the link's DN + attribute mapping, and
 * persists one {@link ReplicationEvent} per matching link.
 *
 * <p>Runs in {@link Propagation#REQUIRES_NEW}: the source-side LDAP
 * write has already happened on the directory by the time we enqueue,
 * and the caller's @Transactional (if any) is for the surrounding
 * portal request, not for this side-effect persistence. Failing to
 * enqueue an event must NOT roll back the caller's transaction —
 * that would tie source-write durability to replication-queue
 * durability, which contradicts the spec's decoupling promise. So:
 * <ul>
 *   <li>REQUIRES_NEW gives us our own tx boundary.</li>
 *   <li>Exceptions are swallowed and logged at error level — the
 *       source write succeeded, the source data is fine; only
 *       replication of this one event is lost. Operators see the
 *       gap as missing target writes; future audit-trail of
 *       enqueue failures lands with the dashboard work in P2.</li>
 * </ul>
 *
 * <p>The {@code findAllBySourceDirectoryIdAndEnabledTrue} query has
 * very high read frequency (every LDAP write triggers it) but very
 * low cardinality (most directories have zero links). The result set
 * is small and the query is indexed; no caching layer in v1.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReplicationEnqueuer {

    private final ReplicationLinkRepository  linkRepo;
    private final ReplicationEventPersister  persister;

    /**
     * Capture point for every successful LDAP write the wrapper sees.
     * Called from {@code ReplicatingLdapInterface} on the hot path,
     * so the no-links case must be cheap: no transaction boundary,
     * no event allocation, no JPA flush. The link lookup is a single
     * indexed SELECT — cheap on its own — but the REQUIRES_NEW tx
     * around it (the previous shape) added BEGIN/COMMIT overhead to
     * every LDAP write in the entire application, even on directories
     * with zero replication links configured. Splitting the
     * transactional save into a sibling bean
     * ({@link ReplicationEventPersister}) keeps the tx cost on the
     * actually-replicating path only.
     */
    public void enqueue(UUID sourceDirectoryId, CapturedWrite write) {
        try {
            List<ReplicationLink> links =
                    linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(sourceDirectoryId);
            if (links.isEmpty()) return;

            List<ReplicationEvent> events = new ArrayList<>(links.size());
            for (ReplicationLink link : links) {
                ReplicationEvent event = buildEvent(link, write);
                if (event != null) events.add(event);
                // null = DN out of scope for this link's source base —
                // skip, don't log per-write (could be very high volume).
            }
            if (events.isEmpty()) return;  // all links out-of-scope for this DN

            persister.saveAll(events);
        } catch (RuntimeException ex) {
            // Source write has already committed against the directory.
            // Don't propagate; log so operators see the gap surfaces in
            // the app log. The dashboard "enqueue failures" surface lands
            // alongside the worker UI in P2.
            log.error("Failed to enqueue replication event for source {} op {} dn {}: {}",
                    sourceDirectoryId, write.operation(), write.dn(), ex.toString());
        }
    }

    private ReplicationEvent buildEvent(ReplicationLink link, CapturedWrite write) {
        String targetDn = DnMapper.map(write.dn(), link);
        if (targetDn == null) {
            return null;
        }
        ReplicationEvent event = new ReplicationEvent();
        event.setLink(link);
        event.setEnqueueSource(ReplicationEnqueueSource.APP_INTERCEPT);
        event.setOperation(write.operation());
        event.setSourceDn(write.dn());
        event.setTargetDn(targetDn);
        event.setStatus(ReplicationEventStatus.PENDING);
        event.setPayload(buildPayload(write, link));
        return event;
    }

    private Map<String, Object> buildPayload(CapturedWrite write, ReplicationLink link) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (write.operation()) {
            case ADD -> payload.put("attributes", mappedAddAttributes(write.attributes(), link));
            case MODIFY -> payload.put("modifications", mappedModifications(write.modifications(), link));
            case DELETE -> { /* empty payload — DN alone identifies the operation */ }
            case MODIFY_DN -> {
                CapturedWrite.ModifyDnParts m = write.modifyDn();
                payload.put("newRdn", m.newRdn());
                payload.put("deleteOldRdn", m.deleteOldRdn());
                payload.put("newSuperiorDn", m.newSuperiorDn());
            }
        }
        return payload;
    }

    private Map<String, List<String>> mappedAddAttributes(List<Attribute> source, ReplicationLink link) {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        for (Attribute a : source) {
            raw.put(a.getName(), Arrays.asList(a.getValues()));
        }
        return AttributeMapper.mapAttributes(raw, link);
    }

    private List<Map<String, Object>> mappedModifications(List<Modification> source, ReplicationLink link) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Modification m : source) {
            AttributeMapper.Mapping mapping = AttributeMapper.mappingFor(m.getAttributeName(), link);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", m.getModificationType().getName().toUpperCase());
            entry.put("name", mapping.targetAttr());
            // m.getValues() returns null (not an empty array) for the
            // "delete the entire attribute" form — new Modification(
            // DELETE, "attrName") with no values. Without this guard
            // a values.length deref NPE-s, the enqueuer's outer
            // try/catch swallows it, and the source-side write
            // silently never replicates.
            String[] values = m.getValues();
            List<String> transformed = values == null
                    ? List.of()
                    : new ArrayList<>(values.length);
            if (values != null) {
                for (String v : values) {
                    transformed.add(mapping.valueTransform().apply(v));
                }
            }
            entry.put("values", transformed);
            out.add(entry);
        }
        return out;
    }
}
