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
     * All enabled links whose source is the given directory. The
     * enqueuer calls this on every write to fan out to matching targets.
     * Returning an empty list on the no-link case is the common path
     * (most directories have zero replication links configured).
     *
     * <p>{@code LEFT JOIN FETCH attributeMappings} is required because
     * the enqueuer runs without an open Hibernate session
     * ({@code open-in-view=false} closes it as soon as this query
     * returns) and then iterates {@code link.getAttributeMappings()}
     * via {@code AttributeMapper}. Without the fetch, that iteration
     * throws {@code LazyInitializationException}, the enqueuer's
     * outer catch swallows it, and every ADD/MODIFY silently fails
     * to land in {@code replication_events}. {@code DISTINCT}
     * deduplicates parents from the join's Cartesian product.
     */
    @Query("""
        SELECT DISTINCT l FROM ReplicationLink l
          LEFT JOIN FETCH l.attributeMappings
         WHERE l.sourceDirectory.id = :sourceDirectoryId
           AND l.enabled = true
        """)
    List<ReplicationLink> findAllBySourceDirectoryIdAndEnabledTrue(
            @Param("sourceDirectoryId") UUID sourceDirectoryId);

    /**
     * Load a single link with its attribute-mapping rules eagerly
     * fetched. Used by {@code ReplicationDelivery.autoCreateThenModify}
     * to apply per-link mapping to the source entry's attributes when
     * auto-creating a missing target entry — that path runs after the
     * Hibernate session has closed, so the regular {@code findById}
     * would trip {@code LazyInitializationException} on the
     * {@code attributeMappings} access.
     */
    @Query("""
        SELECT l FROM ReplicationLink l
          LEFT JOIN FETCH l.attributeMappings
         WHERE l.id = :id
        """)
    Optional<ReplicationLink> findByIdWithMappings(@Param("id") UUID id);
}
