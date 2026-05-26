// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.events.channel.DeliveryOutcome;
import com.ldapportal.core.events.channel.OutboundChannel;
import com.ldapportal.core.events.dto.CreateEventSubscriptionRequest;
import com.ldapportal.core.events.dto.DestinationConfigRequest;
import com.ldapportal.core.events.dto.TestDeliveryResult;
import com.ldapportal.core.events.dto.UpdateEventSubscriptionRequest;
import com.ldapportal.core.events.dto.WebhookAuthRequest;
import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.entity.Account;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.service.EncryptionService;
import com.ldapportal.util.UrlValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD logic for {@link EventSubscription}. Encapsulates encryption of
 * secret fields inside {@code destination_config} on write, so neither the
 * controller nor the entity has to know about ciphertext.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventSubscriptionService {

    private final EventSubscriptionRepository repository;
    private final AccountRepository accountRepository;
    private final EncryptionService encryptionService;
    private final List<OutboundChannel> channels;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<EventSubscription> list(Boolean enabledFilter, ChannelType channelFilter) {
        if (enabledFilter != null && channelFilter != null) {
            return repository.findAllByEnabledAndChannelTypeOrderByCreatedAtDesc(
                    enabledFilter, channelFilter);
        }
        if (enabledFilter != null) {
            return repository.findAllByEnabledOrderByCreatedAtDesc(enabledFilter);
        }
        if (channelFilter != null) {
            return repository.findAllByChannelTypeOrderByCreatedAtDesc(channelFilter);
        }
        return repository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public EventSubscription get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EventSubscription", id));
    }

    @Transactional
    public EventSubscription create(CreateEventSubscriptionRequest req, AuthPrincipal principal) {
        EventSubscription sub = new EventSubscription();
        sub.setName(req.name().trim());
        sub.setDescription(req.description());
        sub.setChannelType(req.channelType());
        sub.setDestinationConfig(buildEncryptedConfig(req.destination()));
        sub.setEventTypeFilter(req.eventTypeFilter());
        sub.setEnabled(req.enabled());
        if (principal != null && principal.id() != null) {
            Account creator = accountRepository.findById(principal.id()).orElse(null);
            sub.setCreatedBy(creator);
        }
        return repository.save(sub);
    }

    @Transactional
    public EventSubscription update(UUID id, UpdateEventSubscriptionRequest req) {
        EventSubscription sub = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EventSubscription", id));
        if (!sub.getVersion().equals(req.version())) {
            throw new ObjectOptimisticLockingFailureException(
                    com.ldapportal.core.events.entity.EventSubscription.class, id);
        }
        // Optimistic lock check: explicit version compare above guards up front;
        // sub.setVersion keeps Hibernate's flush-WHERE also enforcing it.
        sub.setVersion(req.version());
        sub.setName(req.name().trim());
        sub.setDescription(req.description());
        sub.setChannelType(req.channelType());
        sub.setDestinationConfig(buildEncryptedConfig(req.destination()));
        sub.setEventTypeFilter(req.eventTypeFilter());
        sub.setEnabled(req.enabled());
        return repository.save(sub);
    }

    @Transactional
    public EventSubscription setEnabled(UUID id, boolean enabled) {
        EventSubscription sub = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EventSubscription", id));
        sub.setEnabled(enabled);
        return repository.save(sub);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.existsById(id)) {
            throw new ResourceNotFoundException("EventSubscription", id);
        }
        repository.deleteById(id);
    }

    /**
     * Synthetic probe: builds a {@code test.ping} envelope, dispatches directly
     * via the matching channel, returns the outcome. No outbox row, no audit row.
     */
    @Transactional(readOnly = true)
    public TestDeliveryResult testDelivery(UUID id, AuthPrincipal principal) {
        EventSubscription sub = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("EventSubscription", id));

        OutboundChannel channel = channels.stream()
                .filter(c -> c.type() == sub.getChannelType()).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no channel registered for " + sub.getChannelType()));

        OutboxEntry probe = new OutboxEntry();
        probe.setId(UUID.randomUUID());
        probe.setSubscriptionId(sub.getId());
        probe.setEventId(UUID.randomUUID());
        probe.setEventType("test.ping");
        probe.setOccurredAt(clock.instant());
        probe.setStatus(OutboxStatus.DELIVERING);
        probe.setAttempts(1);
        probe.setNextAttemptAt(clock.instant());

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", probe.getEventId().toString());
        envelope.put("type", "test.ping");
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", clock.instant().toString());
        envelope.put("actor", Map.of(
                "id", principal != null ? String.valueOf(principal.id()) : null,
                "username", principal != null ? principal.username() : null,
                "type", principal != null ? principal.type().name() : null));
        envelope.put("resource", Map.of("type", "test", "id", ""));
        envelope.put("payload", Map.of("message", "Test delivery from LDAPPortal"));
        probe.setEnvelope(envelope);

        Instant start = clock.instant();
        DeliveryOutcome outcome;
        try {
            outcome = channel.deliver(probe, sub);
        } catch (RuntimeException e) {
            outcome = DeliveryOutcome.transientFailure("uncaught: " + e.getMessage(), null);
        }
        long elapsedMs = java.time.Duration.between(start, clock.instant()).toMillis();

        return new TestDeliveryResult(
                outcome.kind() == DeliveryOutcome.Kind.SUCCESS,
                outcome.httpStatus(),
                outcome.message() != null ? outcome.message() : "OK",
                elapsedMs);
    }

    // ── internal ──

    private Map<String, Object> buildEncryptedConfig(DestinationConfigRequest req) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        // Reject SSRF-prone destinations (loopback/link-local/metadata) at save
        // time; WebhookChannel re-checks at delivery in case a row is written
        // around this path. IllegalArgumentException → 400 via the handler.
        UrlValidator.requireSafeUrl(req.url());
        cfg.put("url", req.url());
        if (req.auth() != null) {
            cfg.put("auth", buildEncryptedAuth(req.auth()));
        }
        return cfg;
    }

    private Map<String, Object> buildEncryptedAuth(WebhookAuthRequest auth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", auth.type());
        switch (auth.type()) {
            case "bearer" -> {
                if (auth.token() == null || auth.token().isBlank()) {
                    throw new IllegalArgumentException(
                            "bearer auth requires non-blank 'token'");
                }
                if (auth.secret() != null) {
                    throw new IllegalArgumentException(
                            "bearer auth must not include 'secret'");
                }
                m.put("tokenEnc", encryptionService.encrypt(auth.token()));
            }
            case "hmac" -> {
                if (auth.secret() == null || auth.secret().isBlank()) {
                    throw new IllegalArgumentException(
                            "hmac auth requires non-blank 'secret'");
                }
                if (auth.token() != null) {
                    throw new IllegalArgumentException(
                            "hmac auth must not include 'token'");
                }
                m.put("secretEnc", encryptionService.encrypt(auth.secret()));
            }
            default -> throw new IllegalArgumentException(
                    "unknown auth type: " + auth.type());
        }
        return m;
    }
}
