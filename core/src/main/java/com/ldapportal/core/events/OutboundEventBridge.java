// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.OutboundEventType;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
import com.ldapportal.entity.AuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Translates {@link AuditRecordedEvent} into per-subscription
 * {@link OutboxEntry} rows, inside the source transaction's
 * {@code BEFORE_COMMIT} phase.
 *
 * <p>Runs inside the source transaction on purpose: if the outbox insert
 * fails, the source rolls back and no event is lost. The listener does a
 * single translation lookup plus a subscription query plus N inserts — no
 * entitlement checks, no business logic that could spuriously throw.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboundEventBridge {

    private final EventSubscriptionRepository subscriptionRepository;
    private final OutboxEntryRepository outboxRepository;
    private final AuditActionToOutboundTypeMap translation;
    private final Clock clock;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAuditRecorded(AuditRecordedEvent ev) {
        AuditEvent ae = ev.auditEvent();
        Optional<OutboundEventType> maybeType = translation.lookup(ae.getAction());
        if (maybeType.isEmpty()) return;
        OutboundEventType type = maybeType.get();

        // Use JSONB containment: filter matches if filter column is NULL OR
        // contains [type.wireName()]. The repository native query builds the
        // ["wireName"] JSON literal from the raw string.
        String filterJson = "[\"" + type.wireName() + "\"]";
        List<EventSubscription> matching = subscriptionRepository.findActiveMatchingType(filterJson);
        if (matching.isEmpty()) return;

        Map<String, Object> envelope = buildEnvelope(ae, type);
        Instant now = clock.instant();
        for (EventSubscription sub : matching) {
            OutboxEntry row = new OutboxEntry();
            row.setSubscriptionId(sub.getId());
            row.setEventId(UUID.fromString((String) envelope.get("id")));
            row.setEventType(type.wireName());
            row.setOccurredAt(ae.getOccurredAt().toInstant());
            row.setEnvelope(envelope);
            row.setStatus(OutboxStatus.PENDING);
            row.setAttempts(0);
            row.setNextAttemptAt(now);
            outboxRepository.save(row);
        }
    }

    private Map<String, Object> buildEnvelope(AuditEvent ae, OutboundEventType type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", UUID.randomUUID().toString());
        m.put("type", type.wireName());
        m.put("schemaVersion", 1);
        m.put("occurredAt", ae.getOccurredAt().toString());

        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("id", Objects.toString(ae.getActorId(), null));
        actor.put("username", ae.getActorUsername());
        actor.put("type", ae.getActorType());
        m.put("actor", actor);

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", type.resourceType());
        resource.put("id", ae.getTargetDn() != null ? ae.getTargetDn() : "");
        m.put("resource", resource);

        m.put("payload", ae.getDetail() != null ? ae.getDetail() : Map.of());
        return m;
    }
}
