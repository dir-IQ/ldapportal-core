// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.core.util.BackoffPolicies;
import com.ldapportal.core.util.BackoffPolicy;
import com.ldapportal.entity.enums.ReplicationEventStatus;

import java.time.OffsetDateTime;

/**
 * Exponential-backoff schedule for failed replication events.
 *
 * <p>Per the design plan (§3 P1):
 * {@code 30s, 2m, 10m, 1h, 6h → DEAD_LETTERED}. The ladder itself lives
 * in the shared {@link BackoffPolicies#REPLICATION_EVENTS} policy; this
 * class maps a ladder lookup onto replication's status transitions.
 *
 * <p>{@code attempts} on the entity counts <em>total</em> attempts so
 * far. After the first failure, {@code attempts == 1} and the next
 * retry is scheduled 30 seconds out. The fifth failure schedules the
 * final 6h retry; the sixth failure exhausts the retry budget (the
 * ladder has no sixth rung) and the worker transitions the event to
 * {@link ReplicationEventStatus#DEAD_LETTERED} for operator review.
 */
public final class ReplicationBackoffPolicy {

    private static final BackoffPolicy POLICY = BackoffPolicies.REPLICATION_EVENTS;

    /** Visible for tests; the length of the shared replication backoff ladder. */
    public static final int MAX_ATTEMPTS = POLICY.maxAttempts();

    private ReplicationBackoffPolicy() {}

    /**
     * Given the new attempts count after a failure, return the next
     * status + retry timestamp. Returns:
     * <ul>
     *   <li>{@code FAILED} with {@code attemptsAfterFailure}'th delay
     *       added to {@code now}, when retries remain.</li>
     *   <li>{@code DEAD_LETTERED} with null retry, when the budget
     *       is exhausted.</li>
     * </ul>
     */
    public static Outcome computeOutcome(int attemptsAfterFailure, OffsetDateTime now) {
        // attemptsAfterFailure counts failures so far (1 = first
        // failure). The shared ladder has 5 rungs; failures 1..5 schedule
        // retries at 30s, 2m, 10m, 1h, 6h. On the 6th failure the ladder
        // is exhausted (delayForAttempt returns empty) and we dead-letter.
        return POLICY.delayForAttempt(attemptsAfterFailure)
                .map(delay -> new Outcome(ReplicationEventStatus.FAILED, now.plus(delay)))
                .orElseGet(() -> new Outcome(ReplicationEventStatus.DEAD_LETTERED, null));
    }

    public record Outcome(ReplicationEventStatus status, OffsetDateTime nextAttemptAt) {}
}
