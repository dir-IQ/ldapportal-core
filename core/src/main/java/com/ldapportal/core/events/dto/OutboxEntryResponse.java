// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.dto;

import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.EventSubscriptionRepository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record OutboxEntryResponse(
    UUID            id,
    UUID            subscriptionId,
    String          subscriptionName,
    UUID            eventId,
    String          eventType,
    Instant         occurredAt,
    Map<String, Object> envelope,
    OutboxStatus    status,
    int             attempts,
    Instant         nextAttemptAt,
    String          lastError,
    Integer         lastHttpStatus,
    Instant         deliveredAt,
    Instant         deadLetteredAt,
    Instant         createdAt
) {
    public static OutboxEntryResponse from(OutboxEntry e, EventSubscriptionRepository subRepo) {
        String subscriptionName = subRepo.findById(e.getSubscriptionId())
                .map(s -> s.getName())
                .orElse("(deleted)");
        return new OutboxEntryResponse(
                e.getId(),
                e.getSubscriptionId(),
                subscriptionName,
                e.getEventId(),
                e.getEventType(),
                e.getOccurredAt(),
                e.getEnvelope(),
                e.getStatus(),
                e.getAttempts(),
                e.getNextAttemptAt(),
                e.getLastError(),
                e.getLastHttpStatus(),
                e.getDeliveredAt(),
                e.getDeadLetteredAt(),
                e.getCreatedAt()
        );
    }
}
