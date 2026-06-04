// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.observability.CorrelationContext;
import com.ldapportal.entity.enums.ReplicationCaptureMode;
import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
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
            // Cheap short-circuit first: a single indexed SELECT. Doing
            // this before the entitlement gate keeps the per-write license
            // read (FileLicenseProvider re-reads + verifies the JWT on every
            // current() call) off the path when there's nothing to enqueue.
            List<ReplicationLinkSnapshot> links =
                    readOps.snapshotsForSource(sourceDirectoryId);
            if (links.isEmpty()) return;

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

            // Source-side trace id (R2). enqueue runs synchronously on the
            // originating write thread, so the active correlation scope is
            // the originating operation's. currentOrEphemeral() mints one
            // when there's no scope (e.g. a background write) WITHOUT
            // installing it, so a pooled scheduler/async thread doesn't leak
            // the id into its next task. Every event payload gets a non-null
            // id; this work is reached only when there are links to replicate.
            UUID correlationId = CorrelationContext.currentOrEphemeral();

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
        // A CHANGELOG-capture link is fed by the changelog poller, not the live
        // wrapper. Capturing the app's own write here too would double-enqueue
        // it (once by the interceptor, once by the poller) — see design §6.4.
        if (link.captureMode() == ReplicationCaptureMode.CHANGELOG) {
            return null;
        }
        String targetDn = DnMapper.map(write.dn(), link);
        if (targetDn == null) {
            return null;
        }
        // Exclude-filter gate (§7B.2) for the live path: an ADD carries the full
        // entry, so evaluate inline. MODIFY / MODIFY_DN carry only a delta and
        // are evaluated at delivery time (the worker re-reads source); DELETE
        // always propagates.
        if (write.operation() == ReplicationOperationType.ADD
                && ReplicationScopeFilter.hasExcludeFilter(link)
                && ReplicationScopeFilter.isExcluded(link, new Entry(write.dn(), write.attributes()))) {
            return null;
        }
        // Convert the captured write to the shared raw payload shape, then map
        // it through the same ReplicationPayloadMapper the changelog poller uses
        // so both capture paths produce byte-identical queue rows (§6.3).
        Map<String, Object> payload = ReplicationPayloadMapper.buildMappedPayload(
                write.operation(), rawPayload(write), link, correlationId);
        return new PendingReplicationEvent(
                link.id(),
                ReplicationEnqueueSource.APP_INTERCEPT,
                write.operation(),
                write.dn(),
                targetDn,
                payload);
    }

    /**
     * Convert a {@link CapturedWrite} (UnboundID {@code Attribute[]} /
     * {@code Modification[]}) into the pre-mapping raw payload shape the
     * {@link ReplicationPayloadMapper} consumes — identical to what the OUD
     * changelog parser emits.
     */
    private Map<String, Object> rawPayload(CapturedWrite write) {
        Map<String, Object> raw = new LinkedHashMap<>();
        switch (write.operation()) {
            case ADD -> {
                Map<String, List<String>> attrs = new LinkedHashMap<>();
                for (Attribute a : write.attributes()) {
                    attrs.put(a.getName(), Arrays.asList(a.getValues()));
                }
                raw.put("attributes", attrs);
            }
            case MODIFY -> {
                List<Map<String, Object>> mods = new ArrayList<>();
                for (Modification m : write.modifications()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("type", m.getModificationType().getName().toUpperCase());
                    entry.put("name", m.getAttributeName());
                    // getValues() is null for the "delete the whole attribute"
                    // form — keep the same null → List.of() contract the parser
                    // uses so JSONB stays clean and the mapper doesn't NPE.
                    String[] values = m.getValues();
                    entry.put("values", values == null ? List.of() : Arrays.asList(values));
                    mods.add(entry);
                }
                raw.put("modifications", mods);
            }
            case DELETE -> { /* empty */ }
            case MODIFY_DN -> {
                CapturedWrite.ModifyDnParts m = write.modifyDn();
                raw.put("newRdn", m.newRdn());
                raw.put("deleteOldRdn", m.deleteOldRdn());
                raw.put("newSuperiorDn", m.newSuperiorDn());
            }
        }
        return raw;
    }
}
