// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.Membership;
import com.ldapportal.entity.MembershipId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the {@link Membership} index. The engine reads/writes single
 * rows by {@code (sync_set_id, identity)}; reference remapping and the reconcile
 * not-seen sweep use the source-DN and scan-epoch finders.
 */
public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

    List<Membership> findAllBySyncSetId(UUID syncSetId);

    /** Identity currently mapped to a (normalized) source DN within one set. */
    Optional<Membership> findFirstBySyncSetIdAndSourceDn(UUID syncSetId, String sourceDn);

    /** Reference remapping: the membership for a referenced source DN across a link's sets. */
    Optional<Membership> findFirstBySyncSetIdInAndSourceDn(List<UUID> syncSetIds, String sourceDn);

    /** Rows not stamped by the current reconcile epoch (never scanned, or seen in a prior generation). */
    @Query("select m from Membership m where m.syncSetId = :syncSetId "
            + "and (m.lastScanEpoch is null or m.lastScanEpoch < :epoch)")
    List<Membership> findNotSeen(@Param("syncSetId") UUID syncSetId, @Param("epoch") long epoch);

    /** Membership counts grouped by set + state, for the health rollup. */
    @Query("select m.syncSetId as syncSetId, m.state as state, count(m) as cnt "
            + "from Membership m group by m.syncSetId, m.state")
    List<MembershipStateCount> countGroupedByState();
}
