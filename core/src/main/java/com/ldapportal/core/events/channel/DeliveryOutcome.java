// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.channel;

/**
 * Result of a single {@link OutboundChannel#deliver} call. The dispatcher
 * consumes this to decide whether to mark the outbox row DELIVERED,
 * reschedule for retry, or dead-letter.
 */
public record DeliveryOutcome(Kind kind, Integer httpStatus, String message) {

    public enum Kind { SUCCESS, TRANSIENT_FAILURE, PERMANENT_FAILURE }

    public static DeliveryOutcome success(int httpStatus) {
        return new DeliveryOutcome(Kind.SUCCESS, httpStatus, null);
    }

    public static DeliveryOutcome transientFailure(String message, Integer httpStatus) {
        return new DeliveryOutcome(Kind.TRANSIENT_FAILURE, httpStatus, message);
    }

    public static DeliveryOutcome permanentFailure(String message, int httpStatus) {
        return new DeliveryOutcome(Kind.PERMANENT_FAILURE, httpStatus, message);
    }
}
