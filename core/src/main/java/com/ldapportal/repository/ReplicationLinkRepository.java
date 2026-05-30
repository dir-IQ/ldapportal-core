// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.ReplicationLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReplicationLinkRepository extends JpaRepository<ReplicationLink, UUID> {

    /**
     * First link, if any, with exactly this (source, target) directory
     * pair — <b>regardless of {@code enabled} state</b>. Backs the
     * bidirectional-rejection guard: a new A→B link is refused when a
     * B→A link already exists. Matching on enabled-only would let an
     * operator pause B→A, create A→B, then re-enable B→A and have a
     * hidden replication loop, so this intentionally ignores enabled.
     */
    Optional<ReplicationLink> findFirstBySourceDirectoryIdAndTargetDirectoryId(
            UUID sourceDirectoryId, UUID targetDirectoryId);

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
