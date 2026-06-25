// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.entity.enums.SyncChangelogHealth;
import com.ldapportal.repository.RecomputeRequestRepository;
import com.ldapportal.repository.SyncLinkRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the sync-engine gauges snapshot the right repository aggregates and
 * that the lag/health derivations (live age, clamped negative lag, zero-filled
 * health buckets) behave. Repositories are mocked; the meters ride a real
 * {@link SimpleMeterRegistry}.
 */
class SyncEngineMetricsTest {

    private RecomputeRequestRepository recomputeRepo;
    private SyncLinkRepository syncLinkRepo;
    private SimpleMeterRegistry registry;
    private SyncEngineMetrics metrics;

    @BeforeEach
    void setUp() {
        recomputeRepo = mock(RecomputeRequestRepository.class);
        syncLinkRepo = mock(SyncLinkRepository.class);
        registry = new SimpleMeterRegistry();
        metrics = new SyncEngineMetrics(recomputeRepo, syncLinkRepo, registry);
    }

    @Test
    void recompute_queue_depth_inflight_and_lag() {
        when(recomputeRepo.countByClaimedAtIsNull()).thenReturn(7L);
        when(recomputeRepo.countByClaimedAtIsNotNull()).thenReturn(3L);
        when(recomputeRepo.findOldestUnclaimedEnqueuedAt()).thenReturn(OffsetDateTime.now().minusSeconds(45));

        metrics.refresh();

        assertThat(registry.get("ldapportal.sync.recompute.pending").gauge().value()).isEqualTo(7.0);
        assertThat(registry.get("ldapportal.sync.recompute.inflight").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("ldapportal.sync.recompute.oldest.age.seconds").gauge().value())
                .isBetween(44.0, 75.0);
    }

    @Test
    void empty_recompute_queue_reports_zero_age() {
        when(recomputeRepo.findOldestUnclaimedEnqueuedAt()).thenReturn(null);
        metrics.refresh();
        assertThat(registry.get("ldapportal.sync.recompute.oldest.age.seconds").gauge().value()).isZero();
    }

    @Test
    void changelog_links_break_down_by_health_zero_filled() {
        when(syncLinkRepo.countChangelogLinksByHealth()).thenReturn(List.of(
                new Object[]{SyncChangelogHealth.HEALTHY, 4L},
                new Object[]{SyncChangelogHealth.LAGGING, 2L}));

        metrics.refresh();

        assertThat(gaugeHealth("HEALTHY")).isEqualTo(4.0);
        assertThat(gaugeHealth("LAGGING")).isEqualTo(2.0);
        // Every health value is a pre-registered series, zero-filled when absent.
        assertThat(gaugeHealth("STALLED")).isZero();
        assertThat(gaugeHealth("GAP_DETECTED")).isZero();
    }

    @Test
    void changelog_lag_reports_the_max_delta() {
        when(syncLinkRepo.maxChangelogLag()).thenReturn(120L);
        metrics.refresh();
        assertThat(registry.get("ldapportal.sync.changelog.lag.max").gauge().value()).isEqualTo(120.0);
    }

    @Test
    void changelog_lag_clamps_negative_cursor_reset_to_zero() {
        when(syncLinkRepo.maxChangelogLag()).thenReturn(-5L); // head < cursor (cursor reset)
        metrics.refresh();
        assertThat(registry.get("ldapportal.sync.changelog.lag.max").gauge().value()).isZero();
    }

    private double gaugeHealth(String health) {
        return registry.get("ldapportal.sync.changelog.links").tag("health", health).gauge().value();
    }
}
