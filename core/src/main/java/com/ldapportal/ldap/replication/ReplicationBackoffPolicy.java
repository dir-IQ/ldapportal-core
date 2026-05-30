// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ReplicationEventStatus;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Exponential-backoff schedule for failed replication events.
 *
 * <p>Per the design plan (§3 P1):
 * {@code 30s, 2m, 10m, 1h, 6h → DEAD_LETTERED}.
 *
 * <p>{@code attempts} on the entity counts <em>total</em> attempts so
 * far. After the first failure, {@code attempts == 1} and the next
 * retry is scheduled 30 seconds out. After the fifth failure,
 * {@code attempts == 5} — the retry budget is exhausted and the
 * worker transitions the event to {@link ReplicationEventStatus#DEAD_LETTERED}
 * for operator review.
 */
public final class ReplicationBackoffPolicy {

    /**
     * Backoff delays indexed by attempt count - 1 (i.e. delay applied
     * after the Nth attempt fails is at index N-1). The list length
     * is the retry budget: 5 entries → 5 attempts total → DEAD_LETTERED
     * on the 5th failure.
     */
    static final List<Duration> SCHEDULE = List.of(
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofHours(1),
            Duration.ofHours(6)
    );

    /** Visible for tests; identical to {@code SCHEDULE.size()}. */
    public static final int MAX_ATTEMPTS = SCHEDULE.size();

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
        // failure). The schedule has 5 entries indexed 0..4; we use
        // them on failures 1..5 to schedule retries at 30s, 2m, 10m,
        // 1h, 6h respectively. On the 6th failure the budget is gone
        // and we dead-letter. Using `>` (not `>=`) makes the 6h slot
        // reachable.
        if (attemptsAfterFailure > MAX_ATTEMPTS) {
            return new Outcome(ReplicationEventStatus.DEAD_LETTERED, null);
        }
        Duration delay = SCHEDULE.get(attemptsAfterFailure - 1);
        return new Outcome(ReplicationEventStatus.FAILED, now.plus(delay));
    }

    public record Outcome(ReplicationEventStatus status, OffsetDateTime nextAttemptAt) {}
}
