// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.controller;

import com.ldapportal.auth.AuthContextHelper;
import com.ldapportal.core.events.dto.OutboxEntryResponse;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/superadmin/event-outbox")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiredArgsConstructor
public class EventOutboxController {

    private final OutboxEntryRepository outboxRepository;
    private final EventSubscriptionRepository subscriptionRepository;
    private final Clock clock;

    @GetMapping
    public Page<OutboxEntryResponse> list(
            @RequestParam(required = false) OutboxStatus status,
            @RequestParam(required = false) UUID subscriptionId,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<OutboxEntry> entries;
        if (status != null && subscriptionId != null) {
            entries = outboxRepository.findAllBySubscriptionIdAndStatus(subscriptionId, status, pageable);
        } else if (status != null) {
            entries = outboxRepository.findAllByStatus(status, pageable);
        } else if (subscriptionId != null) {
            entries = outboxRepository.findAllBySubscriptionId(subscriptionId, pageable);
        } else if (eventType != null) {
            entries = outboxRepository.findAllByEventType(eventType, pageable);
        } else {
            entries = outboxRepository.findAll(pageable);
        }
        return entries.map(e -> OutboxEntryResponse.from(e, subscriptionRepository));
    }

    @GetMapping("/{id}")
    public OutboxEntryResponse get(@PathVariable UUID id) {
        OutboxEntry e = outboxRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OutboxEntry", id));
        return OutboxEntryResponse.from(e, subscriptionRepository);
    }

    @PostMapping("/{id}/requeue")
    @Transactional
    public OutboxEntryResponse requeue(@PathVariable UUID id) {
        rejectApiTokenCaller();
        OutboxEntry e = outboxRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OutboxEntry", id));
        if (e.getStatus() != OutboxStatus.DEAD_LETTERED) {
            throw new ConflictException(
                    "only DEAD_LETTERED entries can be requeued; this one is " + e.getStatus());
        }
        e.setStatus(OutboxStatus.PENDING);
        e.setAttempts(0);
        e.setNextAttemptAt(clock.instant());
        e.setDeadLetteredAt(null);
        e.setLastError(null);
        return OutboxEntryResponse.from(outboxRepository.save(e), subscriptionRepository);
    }

    private static void rejectApiTokenCaller() {
        if (AuthContextHelper.currentApiToken().isPresent()) {
            throw new AccessDeniedException(
                    "API tokens cannot requeue outbox entries");
        }
    }
}
