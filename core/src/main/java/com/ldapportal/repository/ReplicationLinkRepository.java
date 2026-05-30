// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ReplicationLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReplicationLinkRepository extends JpaRepository<ReplicationLink, UUID> {

    /**
     * All enabled links whose source is the given directory. Called
     * exclusively from {@code ReplicationReadOps.snapshotsForSource},
     * which immediately maps each entity into a
     * {@code ReplicationLinkSnapshot} while still inside the read tx.
     *
     * <p>The {@code LEFT JOIN FETCH}es and {@code JOIN FETCH}es here
     * populate every association the snapshot factory reads —
     * {@code attributeMappings} (collection), {@code sourceDirectory},
     * {@code targetDirectory} — so the snapshot is fully self-contained
     * and the consuming non-transactional code (enqueuer hot path)
     * cannot trip {@code LazyInitializationException} regardless of
     * how it uses the snapshot. {@code DISTINCT} deduplicates the
     * Cartesian-product rows produced by the collection fetch.
     */
    @Query("""
        SELECT DISTINCT l FROM ReplicationLink l
          LEFT JOIN FETCH l.attributeMappings
               JOIN FETCH l.sourceDirectory
               JOIN FETCH l.targetDirectory
         WHERE l.sourceDirectory.id = :sourceDirectoryId
           AND l.enabled = true
        """)
    List<ReplicationLink> findAllBySourceDirectoryIdAndEnabledTrue(
            @Param("sourceDirectoryId") UUID sourceDirectoryId);
}
