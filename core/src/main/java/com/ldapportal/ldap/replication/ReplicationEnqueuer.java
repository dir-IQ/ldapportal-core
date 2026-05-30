// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ReplicationEnqueueSource;
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
import java.util.UUID;

/**
 * Takes a {@link CapturedWrite} from the {@code ReplicatingLDAPInterface}
 * wrapper, finds every enabled {@link ReplicationLinkSnapshot} whose source
 * is the given directory, applies the link's DN + attribute mapping, and
 * hands one {@link PendingReplicationEvent} per matching link to the
 * {@link ReplicationEventPersister} for durable enqueue.
 *
 * <p><b>Architecture:</b> the entire enqueue path operates on immutable
 * snapshot / pending records — no JPA entity reference ever leaves the
 * {@link ReplicationReadOps} read tx, and no JPA entity reference ever
 * enters the {@link ReplicationEventPersister} write tx. Both tx
 * boundaries are owned by sibling beans; this enqueuer is intentionally
 * non-transactional so the no-link hot path costs only a single
 * indexed SELECT with no BEGIN/COMMIT around it.
 *
 * <p>Per the design plan: failing to enqueue an event must NOT roll
 * back the caller's transaction — that would tie source-write
 * durability to replication-queue durability, contradicting the
 * spec's decoupling promise. The outer catch swallows exceptions and
 * logs at error so operators see the gap surface in the app log
 * (and, in P2, in the dashboard's enqueue-failure surface) without
 * the source write itself failing.
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

    private final ReplicationReadOps         readOps;
    private final ReplicationEventPersister  persister;
    /**
     * Community-edition degradation (R2). Spring always wires a bean
     * (a default {@code EntitlementService} is registered via
     * {@code @ConditionalOnMissingBean}); may be {@code null} only in
     * unit tests that construct the enqueuer directly without it, in
     * which case the entitlement gate is treated as open.
     */
    private final EntitlementService         entitlementService;

    /**
     * Capture point for every successful LDAP write the wrapper sees.
     * Called from {@code ReplicatingLdapInterface} on the hot path,
     * so the no-links case must be cheap: no transaction boundary held
     * across the persister call, no event allocation, no JPA flush.
     * The snapshot lookup is a single short read tx owned by
     * {@link ReplicationReadOps}; the persister call only fires when
     * there's actually work to do.
     */
    public void enqueue(UUID sourceDirectoryId, CapturedWrite write) {
        try {
            // Community-edition degradation: when DIRECTORY_SYNC isn't
            // entitled, no events accumulate regardless of the directory's
            // replication_enabled DB value. An entitlement downgrade
            // commercial → community → commercial round-trips cleanly:
            // the column keeps its value, capture simply pauses while
            // unlicensed. (Null in direct-construction unit tests → gate
            // treated as open.)
            if (entitlementService != null
                    && !entitlementService.has(Entitlement.DIRECTORY_SYNC)) {
                return;
            }

            List<ReplicationLinkSnapshot> links =
                    readOps.snapshotsForSource(sourceDirectoryId);
            if (links.isEmpty()) return;

            // Source-side trace id (R2). Prefer one the wrapper stamped on
            // the captured write; otherwise read the active correlation
            // scope, minting one if this write happens outside any (so every
            // replication event payload carries a non-null id). enqueue runs
            // synchronously on the write thread, so this is the originating
            // operation's scope.
            UUID correlationId = write.correlationId() != null
                    ? write.correlationId()
                    : CorrelationContext.currentOrGenerate();

            List<PendingReplicationEvent> pending = new ArrayList<>(links.size());
            for (ReplicationLinkSnapshot link : links) {
                PendingReplicationEvent event = buildEvent(link, write, correlationId);
                if (event != null) pending.add(event);
                // null = DN out of scope for this link's source base —
                // skip, don't log per-write (could be very high volume).
            }
            if (pending.isEmpty()) return;  // all links out-of-scope for this DN

            persister.saveAll(pending);
        } catch (RuntimeException ex) {
            // Source write has already committed against the directory.
            // Don't propagate; log so operators see the gap surfaces in
            // the app log. The dashboard "enqueue failures" surface lands
            // alongside the worker UI in P2.
            log.error("Failed to enqueue replication event for source {} op {} dn {}: {}",
                    sourceDirectoryId, write.operation(), write.dn(), ex.toString());
        }
    }

    private PendingReplicationEvent buildEvent(ReplicationLinkSnapshot link,
                                                 CapturedWrite write,
                                                 UUID correlationId) {
        String targetDn = DnMapper.map(write.dn(), link);
        if (targetDn == null) {
            return null;
        }
        return new PendingReplicationEvent(
                link.id(),
                ReplicationEnqueueSource.APP_INTERCEPT,
                write.operation(),
                write.dn(),
                targetDn,
                buildPayload(write, link, correlationId));
    }

    private Map<String, Object> buildPayload(CapturedWrite write, ReplicationLinkSnapshot link,
                                             UUID correlationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // Source-side trace id travels on the payload so dispatch-side audit
        // rows can pivot back to the originating operation's audit rows.
        if (correlationId != null) {
            payload.put("correlationId", correlationId.toString());
        }
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

    private Map<String, List<String>> mappedAddAttributes(List<Attribute> source,
                                                           ReplicationLinkSnapshot link) {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        for (Attribute a : source) {
            raw.put(a.getName(), Arrays.asList(a.getValues()));
        }
        return AttributeMapper.mapAttributes(raw, link);
    }

    private List<Map<String, Object>> mappedModifications(List<Modification> source,
                                                            ReplicationLinkSnapshot link) {
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
