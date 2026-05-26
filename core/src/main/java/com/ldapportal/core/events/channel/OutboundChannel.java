// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.channel;

import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.ChannelType;

/**
 * Transport SPI. One implementation per {@link ChannelType}. Spring
 * auto-discovers all implementations; {@code OutboundDispatcherScheduler}
 * picks the right one per subscription.
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
    DeliveryOutcome deliver(OutboxEntry row, EventSubscription sub);
}
