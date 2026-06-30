// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.SyncLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link SyncLink}, including the changelog-capture poll lease.
 */
public interface SyncLinkRepository extends JpaRepository<SyncLink, UUID> {

    List<SyncLink> findAllBySourceDirIdAndEnabledTrue(UUID sourceDirId);

    /** Enabled links in CHANGELOG capture mode (the poller's work list). */
    @Query("select l.id from SyncLink l where l.enabled = true and l.captureMode = "
            + "com.ldapportal.entity.enums.SyncCaptureMode.CHANGELOG")
    List<UUID> findChangelogCaptureLinkIds();

    /**
     * Atomically claim the poll lease for one link: succeeds if unclaimed or the
     * existing claim is stale (a crashed poller). Returns 1 if claimed, 0 otherwise.
     */
    @Transactional
    @Modifying
    @Query("update SyncLink l set l.changelogPollClaimedAt = :now where l.id = :id and l.enabled = true "
            + "and (l.changelogPollClaimedAt is null or l.changelogPollClaimedAt < :staleBefore)")
    int claimChangelogPoll(@Param("id") UUID id, @Param("now") OffsetDateTime now,
                           @Param("staleBefore") OffsetDateTime staleBefore);

    // ── Observability (read-only aggregates) ────────────────────────────────────

    /** Count of enabled changelog-capture links grouped by poll health. */
    @Query("select l.changelogHealth, count(l) from SyncLink l "
            + "where l.enabled = true and l.captureMode = "
            + "com.ldapportal.entity.enums.SyncCaptureMode.CHANGELOG "
            + "group by l.changelogHealth")
    List<Object[]> countChangelogLinksByHealth();

    /** Largest source-head-minus-cursor lag across enabled changelog links; null when none. */
    @Query("select max(l.changelogSourceLastChangeNumber - l.changelogLastChangeNumber) "
            + "from SyncLink l where l.enabled = true and l.captureMode = "
            + "com.ldapportal.entity.enums.SyncCaptureMode.CHANGELOG "
            + "and l.changelogSourceLastChangeNumber is not null "
            + "and l.changelogLastChangeNumber is not null")
    Long maxChangelogLag();

    /**
     * Ids of enabled changelog links that are not HEALTHY, worst (largest) lag
     * first (unknown lag last). Lets the dashboard lag awareness deep-link to the
     * most-behind link rather than the full list. {@code changelog_health} is
     * non-null (defaults HEALTHY), so this is the same degraded set the health
     * rollup ({@link #countChangelogLinksByHealth()}) counts.
     */
    @Query("select l.id from SyncLink l "
            + "where l.enabled = true and l.captureMode = "
            + "com.ldapportal.entity.enums.SyncCaptureMode.CHANGELOG "
            + "and l.changelogHealth <> com.ldapportal.entity.enums.SyncChangelogHealth.HEALTHY "
            + "order by (l.changelogSourceLastChangeNumber - l.changelogLastChangeNumber) desc nulls last")
    List<UUID> findDegradedChangelogLinkIdsByLagDesc();
}
