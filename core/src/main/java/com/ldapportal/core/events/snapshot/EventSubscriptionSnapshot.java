// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.snapshot;

import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.enums.ChannelType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable projection of an {@link EventSubscription} for the
 * dispatch path. Holds the fields the dispatcher and channel
 * implementations actually read — name, channelType,
 * destinationConfig, eventTypeFilter, enabled — plus a
 * {@code createdById} UUID rather than the LAZY {@code Account}
 * reference.
 *
 * <p><b>Why this matters.</b> {@code EventSubscription.createdBy}
 * is {@code @ManyToOne(fetch = LAZY)}. The dispatcher loads the
 * subscription via the repository inside its own
 * {@code TransactionTemplate}; with {@code spring.jpa.open-in-view=false},
 * the entity is detached the instant the read returns. If a future
 * channel implementation (or an audit-detail enrichment in
 * {@code WebhookChannel}) ever reads {@code sub.getCreatedBy().get*()},
 * the access raises {@code LazyInitializationException}, the
 * dispatcher's outer catch turns it into a
 * {@code DeliveryOutcome.transientFailure("uncaught: ...")}, and the
 * row enters the retry/dead-letter cycle for no real-world reason.
 * Same bug shape as the one that silently lost replication events
 * for weeks — caught here by typing rather than by code review.
 *
 * <p>If a future feature genuinely needs the creator's identity
 * details, expand this snapshot with denormalised fields
 * ({@code createdByUsername}, {@code createdByDisplayName}) or
 * promote it to its own snapshot type, populated inside the
 * read-side tx — never by holding a live entity reference.
 */
public record EventSubscriptionSnapshot(
        UUID id,
        String name,
        String description,
        ChannelType channelType,
        Map<String, Object> destinationConfig,
        List<String> eventTypeFilter,
        boolean enabled,
        UUID createdById) {

    /**
     * Materialise an entity into a snapshot. MUST be called inside
     * the entity's session — both because {@code createdBy.getId()}
     * triggers a lazy proxy initialisation (cheap, indexed
     * single-row SELECT) and because the deferred initialisation
     * is exactly the failure mode the snapshot exists to eliminate.
     */
    public static EventSubscriptionSnapshot from(EventSubscription s) {
        return new EventSubscriptionSnapshot(
                s.getId(),
                s.getName(),
                s.getDescription(),
                s.getChannelType(),
                s.getDestinationConfig(),
                s.getEventTypeFilter(),
                s.isEnabled(),
                s.getCreatedBy() == null ? null : s.getCreatedBy().getId());
    }
}
