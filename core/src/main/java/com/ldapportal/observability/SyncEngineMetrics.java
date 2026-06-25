// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.entity.enums.SyncChangelogHealth;
import com.ldapportal.repository.RecomputeRequestRepository;
import com.ldapportal.repository.SyncLinkRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Directory-sync engine health metrics (Phase 2 observability): recompute-queue
 * depth/lag and changelog-poll lag/health.
 *
 * <p>These are DB-backed gauges. Rather than query on every Prometheus scrape, a
 * single {@link #refresh()} tick snapshots the counts into in-memory holders and
 * the gauges read those — so scrape rate is decoupled from DB load, and a DB
 * hiccup degrades to a stale snapshot rather than a failed scrape. The two
 * "age" gauges store the oldest timestamp and compute age live at read time, so
 * lag keeps climbing between refreshes (and if the refresh itself stalls).</p>
 *
 * <p>The sync engine is entitlement-gated at runtime ({@code DIRECTORY_SYNC});
 * where it's inactive the tables are empty and every gauge simply reports 0 —
 * an accurate "no backlog" rather than a missing series.</p>
 */
@Component
@Slf4j
public class SyncEngineMetrics {

    private final RecomputeRequestRepository recomputeRepo;
    private final SyncLinkRepository syncLinkRepo;

    private final AtomicLong recomputePending = new AtomicLong();
    private final AtomicLong recomputeInflight = new AtomicLong();
    private final AtomicLong recomputeOldestEpochSec = new AtomicLong();   // 0 = queue empty
    private final AtomicLong changelogMaxLag = new AtomicLong();
    private final EnumMap<SyncChangelogHealth, AtomicLong> changelogByHealth =
            new EnumMap<>(SyncChangelogHealth.class);

    public SyncEngineMetrics(RecomputeRequestRepository recomputeRepo,
                             SyncLinkRepository syncLinkRepo,
                             MeterRegistry registry) {
        this.recomputeRepo = recomputeRepo;
        this.syncLinkRepo = syncLinkRepo;
        for (SyncChangelogHealth health : SyncChangelogHealth.values()) {
            changelogByHealth.put(health, new AtomicLong());
        }
        bind(registry);
    }

    private void bind(MeterRegistry registry) {
        Gauge.builder("ldapportal.sync.recompute.pending", recomputePending, AtomicLong::doubleValue)
                .description("Unclaimed recompute requests waiting in the coalescing queue (queue depth)")
                .baseUnit("requests").register(registry);
        Gauge.builder("ldapportal.sync.recompute.inflight", recomputeInflight, AtomicLong::doubleValue)
                .description("Recompute requests currently claimed by a worker")
                .baseUnit("requests").register(registry);
        Gauge.builder("ldapportal.sync.recompute.oldest.age.seconds", recomputeOldestEpochSec,
                        f -> MetricAges.liveSeconds(f.get()))
                .description("Age of the oldest unclaimed recompute request (queue lag); 0 when empty")
                .baseUnit("seconds").register(registry);
        Gauge.builder("ldapportal.sync.changelog.lag.max", changelogMaxLag, AtomicLong::doubleValue)
                .description("Largest changelog lag (source head minus cursor) across enabled changelog links")
                .baseUnit("changes").register(registry);
        changelogByHealth.forEach((health, value) ->
                Gauge.builder("ldapportal.sync.changelog.links", value, AtomicLong::doubleValue)
                        .description("Enabled changelog-capture links by poll health")
                        .tag("health", health.name())
                        .baseUnit("links").register(registry));
    }

    /**
     * Prime the snapshot once at startup so the first scrape after a restart
     * reports real values instead of zeros (the scheduled refresh otherwise
     * first runs only after the initial delay). {@link #refresh()} swallows its
     * own failures, so a startup DB hiccup just leaves the zeros in place.
     */
    @PostConstruct
    void primeOnStartup() {
        refresh();
    }

    @Scheduled(initialDelayString = "${ldapportal.metrics.refresh-ms:15000}",
               fixedDelayString = "${ldapportal.metrics.refresh-ms:15000}")
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            recomputePending.set(recomputeRepo.countByClaimedAtIsNull());
            recomputeInflight.set(recomputeRepo.countByClaimedAtIsNotNull());
            OffsetDateTime oldest = recomputeRepo.findOldestUnclaimedEnqueuedAt();
            recomputeOldestEpochSec.set(oldest == null ? 0L : oldest.toEpochSecond());

            Long maxLag = syncLinkRepo.maxChangelogLag();
            changelogMaxLag.set(maxLag == null ? 0L : Math.max(0L, maxLag));

            EnumMap<SyncChangelogHealth, Long> counts = new EnumMap<>(SyncChangelogHealth.class);
            for (Object[] row : syncLinkRepo.countChangelogLinksByHealth()) {
                counts.put((SyncChangelogHealth) row[0], (Long) row[1]);
            }
            changelogByHealth.forEach((health, value) -> value.set(counts.getOrDefault(health, 0L)));
        } catch (RuntimeException e) {
            // Metrics refresh must never disrupt the app; keep the last snapshot.
            log.debug("Sync engine metrics refresh failed: {}", e.toString());
        }
    }
}
