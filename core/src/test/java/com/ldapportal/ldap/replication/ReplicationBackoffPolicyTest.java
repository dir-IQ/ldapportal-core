// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.enums.ReplicationEventStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ReplicationBackoffPolicyTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-05-30T12:00:00Z");

    @Test
    void firstFailure_schedules30sRetry() {
        // After the first delivery failure (attempts=1), the next retry
        // is 30 seconds out and the event stays FAILED for the worker
        // to pick up again.
        ReplicationBackoffPolicy.Outcome out =
                ReplicationBackoffPolicy.computeOutcome(1, NOW);

        assertThat(out.status()).isEqualTo(ReplicationEventStatus.FAILED);
        assertThat(out.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofSeconds(30)));
    }

    @Test
    void scheduleProgressesExponentially() {
        // 30s, 2m, 10m, 1h, 6h — pin the curve so a future "tweak the
        // schedule" PR can't silently change the operator-visible lag.
        assertThat(ReplicationBackoffPolicy.computeOutcome(1, NOW).nextAttemptAt())
                .isEqualTo(NOW.plus(Duration.ofSeconds(30)));
        assertThat(ReplicationBackoffPolicy.computeOutcome(2, NOW).nextAttemptAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        assertThat(ReplicationBackoffPolicy.computeOutcome(3, NOW).nextAttemptAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(ReplicationBackoffPolicy.computeOutcome(4, NOW).nextAttemptAt())
                .isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(ReplicationBackoffPolicy.computeOutcome(5, NOW).nextAttemptAt())
                .isEqualTo(NOW.plus(Duration.ofHours(6)));
    }

    @Test
    void fifthFailure_usesFinalScheduleSlot_stillFailedNotDeadLettered() {
        // attemptsAfterFailure == MAX_ATTEMPTS (5) means we just
        // consumed the last schedule slot — the 6h retry — and the
        // event waits for one more attempt. Pinning the boundary
        // explicitly so a future "MAX_ATTEMPTS now means something
        // else" refactor can't silently move the dead-letter line.
        ReplicationBackoffPolicy.Outcome out =
                ReplicationBackoffPolicy.computeOutcome(
                        ReplicationBackoffPolicy.MAX_ATTEMPTS, NOW);
        assertThat(out.status()).isEqualTo(ReplicationEventStatus.FAILED);
        assertThat(out.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofHours(6)));
    }

    @Test
    void sixthFailure_transitionsToDeadLettered() {
        // After the 6th failure, no schedule slot remains — the worker
        // dead-letters the event. Total attempt count from the
        // operator's perspective: 1 original + 5 retries.
        ReplicationBackoffPolicy.Outcome out =
                ReplicationBackoffPolicy.computeOutcome(
                        ReplicationBackoffPolicy.MAX_ATTEMPTS + 1, NOW);

        assertThat(out.status()).isEqualTo(ReplicationEventStatus.DEAD_LETTERED);
        assertThat(out.nextAttemptAt()).isNull();
    }
}
