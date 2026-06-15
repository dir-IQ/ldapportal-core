// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.AuditSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboundEventBridgeTest {

    @Mock private EventSubscriptionRepository subscriptionRepository;
    @Mock private OutboxEntryRepository outboxRepository;

    private final AuditActionToOutboundTypeMap translation = new AuditActionToOutboundTypeMap();
    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-04-23T10:00:00Z"), ZoneOffset.UTC);

    private OutboundEventBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new OutboundEventBridge(subscriptionRepository, outboxRepository, translation, fixedClock);
    }

    @Test
    void unmappedAction_skipsSilently() {
        AuditEvent ae = auditEventWith(AuditAction.LDAP_CHANGE);
        bridge.onAuditRecorded(new AuditRecordedEvent(ae));
        verify(subscriptionRepository, never()).findActiveMatchingType(anyString());
        verify(outboxRepository, never()).save(any(OutboxEntry.class));
    }

    @Test
    void noMatchingSubscription_skipsSilently() {
        AuditEvent ae = auditEventWith(AuditAction.API_TOKEN_CREATED);
        when(subscriptionRepository.findActiveMatchingType(anyString())).thenReturn(List.of());
        bridge.onAuditRecorded(new AuditRecordedEvent(ae));
        verify(outboxRepository, never()).save(any(OutboxEntry.class));
    }

    @Test
    void matchingSubscription_insertsOutboxRow() {
        AuditEvent ae = auditEventWith(AuditAction.API_TOKEN_CREATED);
        EventSubscription sub = subscription();
        when(subscriptionRepository.findActiveMatchingType(anyString())).thenReturn(List.of(sub));

        bridge.onAuditRecorded(new AuditRecordedEvent(ae));

        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository, times(1)).save(captor.capture());
        OutboxEntry row = captor.getValue();
        assertThat(row.getSubscriptionId()).isEqualTo(sub.getId());
        assertThat(row.getEventType()).isEqualTo("api_token.created");
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(0);
        assertThat(row.getNextAttemptAt()).isEqualTo(fixedClock.instant());
    }

    @Test
    void multipleMatchingSubscriptions_insertsNRows() {
        AuditEvent ae = auditEventWith(AuditAction.API_TOKEN_CREATED);
        when(subscriptionRepository.findActiveMatchingType(anyString()))
                .thenReturn(List.of(subscription(), subscription(), subscription()));

        bridge.onAuditRecorded(new AuditRecordedEvent(ae));

        verify(outboxRepository, times(3)).save(any(OutboxEntry.class));
    }

    @Test
    void envelopeHasStableShape() {
        AuditEvent ae = AuditEvent.builder()
                .action(AuditAction.API_TOKEN_CREATED)
                .source(AuditSource.INTERNAL)
                .actorId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .actorType("SUPERADMIN")
                .actorUsername("alice")
                .occurredAt(OffsetDateTime.parse("2026-04-23T10:00:00Z"))
                .detail(Map.of("tokenId", "t-123", "tokenName", "ci"))
                .build();

        when(subscriptionRepository.findActiveMatchingType(anyString()))
                .thenReturn(List.of(subscription()));
        bridge.onAuditRecorded(new AuditRecordedEvent(ae));

        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository).save(captor.capture());
        Map<String, Object> envelope = captor.getValue().getEnvelope();

        assertThat(envelope).containsKeys("id", "type", "schemaVersion", "occurredAt",
                "actor", "resource", "payload");
        assertThat(envelope.get("type")).isEqualTo("api_token.created");
        assertThat(envelope.get("schemaVersion")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> actor = (Map<String, Object>) envelope.get("actor");
        assertThat(actor.get("username")).isEqualTo("alice");
        assertThat(actor.get("type")).isEqualTo("SUPERADMIN");

        @SuppressWarnings("unchecked")
        Map<String, Object> resource = (Map<String, Object>) envelope.get("resource");
        assertThat(resource.get("type")).isEqualTo("api_token");

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload).containsEntry("tokenName", "ci");
    }

    @Test
    void nullActorFieldsHandled() {
        AuditEvent ae = AuditEvent.builder()
                .action(AuditAction.API_TOKEN_CREATED)
                .source(AuditSource.INTERNAL)
                .actorId(null)
                .actorType("SUPERADMIN")
                .actorUsername(null)
                .occurredAt(OffsetDateTime.parse("2026-04-23T10:00:00Z"))
                .detail(Map.of())
                .build();
        when(subscriptionRepository.findActiveMatchingType(anyString()))
                .thenReturn(List.of(subscription()));

        // Must not throw.
        bridge.onAuditRecorded(new AuditRecordedEvent(ae));

        verify(outboxRepository).save(any(OutboxEntry.class));
    }

    @Test
    void nullDetailHandled() {
        AuditEvent ae = AuditEvent.builder()
                .action(AuditAction.API_TOKEN_CREATED)
                .source(AuditSource.INTERNAL)
                .actorType("SUPERADMIN")
                .actorUsername("alice")
                .actorId(UUID.randomUUID())
                .occurredAt(OffsetDateTime.parse("2026-04-23T10:00:00Z"))
                .detail(null)
                .build();
        when(subscriptionRepository.findActiveMatchingType(anyString()))
                .thenReturn(List.of(subscription()));

        bridge.onAuditRecorded(new AuditRecordedEvent(ae));

        ArgumentCaptor<OutboxEntry> captor = ArgumentCaptor.forClass(OutboxEntry.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEnvelope().get("payload")).isEqualTo(Map.of());
    }

    // ── helpers ──

    private static AuditEvent auditEventWith(AuditAction action) {
        return AuditEvent.builder()
                .action(action)
                .source(AuditSource.INTERNAL)
                .actorType("SUPERADMIN")
                .actorUsername("alice")
                .actorId(UUID.randomUUID())
                .occurredAt(OffsetDateTime.parse("2026-04-23T10:00:00Z"))
                .detail(Map.of())
                .build();
    }

    private static EventSubscription subscription() {
        EventSubscription sub = new EventSubscription();
        sub.setId(UUID.randomUUID());
        sub.setName("test-sub");
        sub.setChannelType(ChannelType.WEBHOOK);
        sub.setEnabled(true);
        return sub;
    }
}
