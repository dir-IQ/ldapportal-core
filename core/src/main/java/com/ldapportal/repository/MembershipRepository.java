// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.Membership;
import com.ldapportal.entity.MembershipId;
import com.ldapportal.entity.enums.MembershipState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * Paged inventory for one set, optionally filtered by state and by a
     * case-insensitive substring over identity / source DN / target DN.
     * {@code state} null = all states; {@code q} null/blank = no text filter
     * (callers pass {@code q} already lower-cased and wrapped in {@code %…%}).
     */
    @Query("select m from Membership m where m.syncSetId = :syncSetId "
            + "and (:state is null or m.state = :state) "
            + "and (:q is null or lower(m.identity) like :q "
            + "     or lower(m.sourceDn) like :q or lower(m.targetDn) like :q)")
    Page<Membership> search(@Param("syncSetId") UUID syncSetId,
                            @Param("state") MembershipState state,
                            @Param("q") String q,
                            Pageable pageable);
}
