// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.util;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Nominal exponential-backoff schedule shared by the async-dispatch
 * subsystems (replication events, outbound events). A policy is just a
 * <em>ladder</em> of delays; the Nth retry after a failure waits the
 * Nth rung. The ladder length is the retry budget — once a failure
 * count exceeds it, {@link #delayForAttempt} returns empty, which the
 * caller reads as "dead-letter".
 *
 * <p><b>Jitter is deliberately not modelled here.</b> Jitter is a
 * delivery concern (<em>when</em> to actually fire), not a
 * backoff-schedule concern (<em>what</em> the nominal delay is). A
 * subsystem that wants jitter wraps the returned delay at its own call
 * site; a subsystem that wants the schedule honoured exactly (e.g.
 * replication, whose operator-visible lag is pinned by test) simply
 * doesn't wrap. Sharing the <em>type</em> lets both subsystems tune to
 * different ladders ({@link BackoffPolicies}) without duplicating the
 * indexing arithmetic that previously diverged subtly between them.
 */
public record BackoffPolicy(List<Duration> ladder) {

    public BackoffPolicy {
        ladder = List.copyOf(ladder);
    }

    /** The retry budget — the number of scheduled retries before dead-letter. */
    public int maxAttempts() {
        return ladder.size();
    }

    /**
     * The delay before the retry that follows {@code attemptsAfterFailure}
     * failures. {@code attemptsAfterFailure == 1} is the delay after the
     * first failure (the first ladder rung). Returns empty once the
     * count exceeds the ladder length, signalling the retry budget is
     * exhausted and the caller should dead-letter.
     */
    public Optional<Duration> delayForAttempt(int attemptsAfterFailure) {
        if (attemptsAfterFailure < 1 || attemptsAfterFailure > ladder.size()) {
            return Optional.empty();
        }
        return Optional.of(ladder.get(attemptsAfterFailure - 1));
    }
}
