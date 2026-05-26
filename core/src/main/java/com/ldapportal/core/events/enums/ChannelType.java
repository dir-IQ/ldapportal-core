// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.enums;

/**
 * Transport type for an {@code EventSubscription}. v1 ships only WEBHOOK.
 * Future values (SLACK, KAFKA) register as additional {@code OutboundChannel}
 * beans without schema changes.
 */
public enum ChannelType { WEBHOOK }
