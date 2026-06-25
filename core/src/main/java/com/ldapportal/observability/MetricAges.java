// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import java.time.Instant;

/**
 * Shared helper for "age since a stored epoch-second timestamp" gauges
 * ({@link SyncEngineMetrics}, {@link JobHealthMetrics}). The age is computed
 * live at read time so a backlog's age keeps climbing between snapshot
 * refreshes (and if a refresh stalls).
 */
final class MetricAges {

    private MetricAges() {}

    /** Live age in seconds for a stored epoch-seconds value; 0 (or non-positive) means "none". */
    static double liveSeconds(long epochSeconds) {
        if (epochSeconds <= 0L) {
            return 0.0;
        }
        return Math.max(0.0, Instant.now().getEpochSecond() - epochSeconds);
    }
}
