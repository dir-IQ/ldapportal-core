// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.util;

import java.time.Duration;
import java.util.List;

/**
 * The concrete {@link BackoffPolicy} ladders in use. The two subsystems
 * share the type but tune to different ladders; a future "harmonize the
 * ladders" change is a one-constant edit here.
 */
public final class BackoffPolicies {

    /**
     * Outbound-event (webhook) delivery: {@code 1m, 5m, 15m, 1h, 6h}.
     * The dispatcher adds jitter to each rung at its call site.
     */
    public static final BackoffPolicy OUTBOUND_EVENTS = new BackoffPolicy(List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
            Duration.ofHours(1),   Duration.ofHours(6)));

    /**
     * Replication-event delivery: {@code 30s, 2m, 10m, 1h, 6h}. No
     * jitter — the schedule is honoured exactly so operator-visible lag
     * stays predictable.
     */
    public static final BackoffPolicy REPLICATION_EVENTS = new BackoffPolicy(List.of(
            Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10),
            Duration.ofHours(1),    Duration.ofHours(6)));

    private BackoffPolicies() {}
}
