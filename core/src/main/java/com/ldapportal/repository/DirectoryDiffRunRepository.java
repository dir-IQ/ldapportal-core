// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.DirectoryDiffRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface DirectoryDiffRunRepository extends JpaRepository<DirectoryDiffRun, UUID> {

    /** The latest runs, newest first. */
    List<DirectoryDiffRun> findTop20ByOrderByStartedAtDesc();

    /** A pair of directories' runs, newest first (the retention sweep keeps the first few). */
    List<DirectoryDiffRun> findByDirectoryAIdAndDirectoryBIdOrderByStartedAtDesc(UUID directoryAId, UUID directoryBId);

    /** Runs still going: RUNNING and heartbeated since {@code freshSince}. */
    @Query("select count(r) from DirectoryDiffRun r where r.status = com.ldapportal.entity.enums.DirectoryDiffStatus.RUNNING "
            + "and r.heartbeatAt > :freshSince")
    long countRunning(@Param("freshSince") OffsetDateTime freshSince);

    /** Ask a run to stop (a targeted update, so the run's own saves can't undo it). */
    @Transactional
    @Modifying
    @Query("update DirectoryDiffRun r set r.cancelRequested = true where r.id = :id "
            + "and r.status = com.ldapportal.entity.enums.DirectoryDiffStatus.RUNNING")
    int requestCancel(@Param("id") UUID id);

    @Query("select r.cancelRequested from DirectoryDiffRun r where r.id = :id")
    Boolean isCancelRequested(@Param("id") UUID id);
}
