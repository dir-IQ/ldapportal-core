// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ReplicationEventRepository eventRepo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(UUID sourceDirectoryId, CapturedWrite write) {
        try {
            List<ReplicationLink> links =
                    linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(sourceDirectoryId);
            if (links.isEmpty()) return;

            for (ReplicationLink link : links) {
                ReplicationEvent event = buildEvent(link, write);
                if (event == null) {
                    // DN out of scope for this link's source base — skip,
                    // don't log per-write (could be very high volume).
                    continue;
                }
                eventRepo.save(event);
            }
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
            String[] values = m.getValues();
            List<String> transformed = new ArrayList<>(values.length);
            for (String v : values) {
                transformed.add(mapping.valueTransform().apply(v));
            }
            entry.put("values", transformed);
            out.add(entry);
        }
        return out;
    }
}
