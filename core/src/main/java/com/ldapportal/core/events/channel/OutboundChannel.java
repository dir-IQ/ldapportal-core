// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.channel;

import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.snapshot.EventSubscriptionSnapshot;
import com.ldapportal.core.events.snapshot.OutboxEntrySnapshot;

/**
 * Transport SPI. One implementation per {@link ChannelType}. Spring
 * auto-discovers all implementations; {@code OutboundDispatcherScheduler}
 * picks the right one per subscription.
 *
 * <p>The callback receives <em>snapshot records</em> rather than JPA
 * entities — see {@link OutboxEntrySnapshot} /
 * {@link EventSubscriptionSnapshot} for the rationale. In short:
 * channels run outside any DB transaction and frequently perform
 * network I/O that can take seconds; passing JPA entities here would
 * couple delivery correctness to invisible Hibernate session-lifecycle
 * details, and any access to a LAZY association (e.g. a future
 * {@code sub.getCreatedBy()} call) would silently fail through the
 * dispatcher's generic transient-failure catch and re-enter the retry
 * loop forever.
 */
public interface OutboundChannel {

    /** Which channel-type this impl serves. */
    ChannelType type();

    /**
     * Deliver the envelope in {@code row} to the destination encoded in
     * {@code sub}. Must not throw — catch everything and return a
     * {@link DeliveryOutcome#transientFailure}. The dispatcher wraps in an
     * outer try/catch anyway, but channels are expected to be well-behaved.
     */
    DeliveryOutcome deliver(OutboxEntrySnapshot row, EventSubscriptionSnapshot sub);
}
