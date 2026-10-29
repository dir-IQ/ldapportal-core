// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * In-scope source entries a sync set doesn't manage, as its latest complete
 * reconcile found them: why the run's source entries outnumber the managed ones.
 *
 * @param runId          that reconcile, or {@code null} when the set has none yet
 * @param finishedAt     when it finished
 * @param allDnsRecorded every entry is listed (the set's logging level was
 *                       Detailed); otherwise only up to {@code sampleLimit} per reason
 * @param total          not-in-set entries the run counted
 * @param reasons        the count per reason, most first
 * @param entries        this page of entries
 * @param matched        entries matching the search (the listed ones, not {@code total},
 *                       when only samples were kept)
 * @param page           this page (0-based)
 * @param size           entries per page
 * @param sampleLimit    entries kept per reason when not all are
 */
public record SyncNotInSetPage(UUID runId, OffsetDateTime finishedAt, boolean allDnsRecorded, int total,
                               List<SyncNotInSetGroup> reasons, List<Entry> entries, long matched,
                               int page, int size, int sampleLimit) {

    /** One not-in-set entry: its source DN and why. */
    public record Entry(String sourceDn, String reason, String label) {
    }
}
