// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.enums.SyncCaptureMode;
import com.ldapportal.entity.enums.SyncChangelogHealth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the Phase-2 observability aggregate queries against H2 (PostgreSQL
 * mode) — the risky ones the mocked-repo unit tests can't reach: the enum
 * group-by ({@code countChangelogLinksByHealth}) and the nullable arithmetic
 * {@code max} ({@code maxChangelogLag}), plus the recompute-queue counts.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SyncMetricsRepositoryTest {

    @Autowired private SyncLinkRepository syncLinkRepo;
    @Autowired private RecomputeRequestRepository recomputeRepo;

    /**
     * This runs {@code @DataJpaTest} with {@code replace = NONE}, i.e. against the
     * shared JVM-wide H2 test database ({@code jdbc:h2:mem:ldapportal-test;
     * DB_CLOSE_DELAY=-1}). Committed rows written by other {@code @SpringBootTest}
     * suites in the same run therefore remain visible here and would break these
     * "empty set" / exact-aggregate assertions depending on test order. Start every
     * test from a known clean slate for the two tables it queries, mirroring the
     * {@code @BeforeEach} cleanup already used by {@code SyncChangelogPollerTest}.
     */
    @BeforeEach
    void clearSharedState() {
        syncLinkRepo.deleteAll();
        recomputeRepo.deleteAll();
    }

    private SyncLink link(SyncCaptureMode mode, SyncChangelogHealth health,
                          Long sourceHead, Long cursor, boolean enabled) {
        SyncLink l = new SyncLink();
        l.setDisplayName("link-" + UUID.randomUUID());
        l.setSourceDirId(UUID.randomUUID());
        l.setTargetDirId(UUID.randomUUID());
        l.setEnabled(enabled);
        l.setCaptureMode(mode);
        l.setChangelogHealth(health);
        l.setChangelogSourceLastChangeNumber(sourceHead);
        l.setChangelogLastChangeNumber(cursor);
        return l;
    }

    private RecomputeRequest req(boolean claimed) {
        RecomputeRequest r = new RecomputeRequest();
        r.setSyncSetId(UUID.randomUUID());
        r.setRequestKey("key-" + UUID.randomUUID());
        if (claimed) {
            r.setClaimedAt(OffsetDateTime.now());
        }
        return r;
    }

    @Test
    void changelog_links_aggregate_by_health_and_max_lag() {
        syncLinkRepo.save(link(SyncCaptureMode.CHANGELOG, SyncChangelogHealth.LAGGING, 100L, 80L, true)); // lag 20
        syncLinkRepo.save(link(SyncCaptureMode.CHANGELOG, SyncChangelogHealth.HEALTHY, 50L, 50L, true));  // lag 0
        syncLinkRepo.save(link(SyncCaptureMode.CHANGELOG, SyncChangelogHealth.STALLED, null, null, true)); // no lag data
        syncLinkRepo.save(link(SyncCaptureMode.APP_INTERCEPT, SyncChangelogHealth.HEALTHY, 999L, 0L, true)); // not changelog
        syncLinkRepo.save(link(SyncCaptureMode.CHANGELOG, SyncChangelogHealth.LAGGING, 200L, 100L, false));  // disabled
        syncLinkRepo.flush();

        // Only enabled CHANGELOG links with both numbers present contribute: max(20, 0) = 20.
        assertThat(syncLinkRepo.maxChangelogLag()).isEqualTo(20L);

        Map<SyncChangelogHealth, Long> byHealth = new EnumMap<>(SyncChangelogHealth.class);
        for (Object[] row : syncLinkRepo.countChangelogLinksByHealth()) {
            byHealth.put((SyncChangelogHealth) row[0], (Long) row[1]);
        }
        // Enabled CHANGELOG only: the APP_INTERCEPT and the disabled link are excluded.
        assertThat(byHealth)
                .containsEntry(SyncChangelogHealth.LAGGING, 1L)
                .containsEntry(SyncChangelogHealth.HEALTHY, 1L)
                .containsEntry(SyncChangelogHealth.STALLED, 1L)
                .doesNotContainKey(SyncChangelogHealth.GAP_DETECTED);
    }

    @Test
    void empty_changelog_set_has_null_max_lag() {
        assertThat(syncLinkRepo.maxChangelogLag()).isNull();
        assertThat(syncLinkRepo.countChangelogLinksByHealth()).isEmpty();
    }

    @Test
    void recompute_queue_counts_and_oldest_unclaimed() {
        recomputeRepo.save(req(false));
        recomputeRepo.save(req(false));
        recomputeRepo.save(req(true));
        recomputeRepo.flush();

        assertThat(recomputeRepo.countByClaimedAtIsNull()).isEqualTo(2);
        assertThat(recomputeRepo.countByClaimedAtIsNotNull()).isEqualTo(1);
        assertThat(recomputeRepo.findOldestUnclaimedEnqueuedAt()).isNotNull();
    }

    @Test
    void empty_recompute_queue_has_null_oldest() {
        assertThat(recomputeRepo.findOldestUnclaimedEnqueuedAt()).isNull();
    }
}
