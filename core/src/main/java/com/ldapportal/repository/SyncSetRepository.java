// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.SyncSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link SyncSet}. Phase-0 mapping only.
 */
public interface SyncSetRepository extends JpaRepository<SyncSet, UUID> {

    List<SyncSet> findAllByLinkId(UUID linkId);

    List<SyncSet> findAllByEnabledTrue();

    /**
     * Cache a content-verify outcome on the set (drift snapshot for the dashboard).
     * A targeted update so it never collides with the optimistic-lock version the
     * config/engine paths increment — these are denormalized stats, not config.
     */
    @Transactional
    @Modifying
    @Query("update SyncSet s set s.lastVerifiedAt = :at, s.verifyMissingCount = :missing, "
            + "s.verifyOrphanCount = :orphan, s.verifyMismatchCount = :mismatch where s.id = :id")
    int recordVerifyResult(@Param("id") UUID id, @Param("at") OffsetDateTime at,
                           @Param("missing") int missing, @Param("orphan") int orphan,
                           @Param("mismatch") int mismatch);
}
