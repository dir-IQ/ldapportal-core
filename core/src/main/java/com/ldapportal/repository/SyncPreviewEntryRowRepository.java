// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.SyncPreviewEntryRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Persistence for the planned entries of a {@link com.ldapportal.entity.SyncPreviewRun}. */
public interface SyncPreviewEntryRowRepository
        extends JpaRepository<SyncPreviewEntryRow, SyncPreviewEntryRow.Key> {

    /** One page of a run's entries, in display order. */
    List<SyncPreviewEntryRow> findByRunIdOrderByOrdinalAsc(UUID runId, Pageable page);

    /** One page of a run's entries the "changes only" view lists, in display order. */
    List<SyncPreviewEntryRow> findByRunIdAndActionableTrueOrderByOrdinalAsc(UUID runId, Pageable page);

    /** Drop a set's entries from every run but {@code keepRunId} (the run just started). */
    @Transactional
    @Modifying
    @Query("delete from SyncPreviewEntryRow e where e.syncSetId = :setId and e.runId <> :keepRunId")
    int deleteOtherRuns(@Param("setId") UUID setId, @Param("keepRunId") UUID keepRunId);

    /** Entries of the runs {@link SyncPreviewRunRepository#deleteInactiveSince} is about to remove. */
    @Transactional
    @Modifying
    @Query("delete from SyncPreviewEntryRow e where e.syncSetId in "
            + "(select r.syncSetId from SyncPreviewRun r where r.heartbeatAt < :before)")
    int deleteForRunsInactiveSince(@Param("before") OffsetDateTime before);
}
