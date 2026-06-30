// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
import com.ldapportal.core.reports.schedule.ReportJobRunStatus;
import com.ldapportal.repository.ScheduledReportJobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Background-job health metrics (Phase 2 observability): event-outbox delivery
 * backlog and scheduled-report-job status. Both subsystems run unconditionally
 * in core (no entitlement gating), so these gauges are always meaningful.
 *
 * <p>Same DB-backed snapshot pattern as {@link SyncEngineMetrics}: one
 * {@link #refresh()} tick reads the repository aggregates into in-memory
 * holders; gauges read those, and the backlog-age gauge computes age live so it
 * keeps climbing between refreshes.</p>
 */
@Component
@Slf4j
public class JobHealthMetrics {

    private final OutboxEntryRepository outboxRepo;
    private final ScheduledReportJobRepository reportJobRepo;

    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong outboxDelivering = new AtomicLong();
    private final AtomicLong outboxDeadLettered = new AtomicLong();
    private final AtomicLong outboxOldestPendingEpochSec = new AtomicLong();   // 0 = none pending
    private final AtomicLong reportJobsEnabled = new AtomicLong();
    private final AtomicLong reportJobsFailed = new AtomicLong();

    public JobHealthMetrics(OutboxEntryRepository outboxRepo,
                            ScheduledReportJobRepository reportJobRepo,
                            MeterRegistry registry) {
        this.outboxRepo = outboxRepo;
        this.reportJobRepo = reportJobRepo;
        bind(registry);
    }

    private void bind(MeterRegistry registry) {
        outboxGauge(registry, OutboxStatus.PENDING, outboxPending);
        outboxGauge(registry, OutboxStatus.DELIVERING, outboxDelivering);
        outboxGauge(registry, OutboxStatus.DEAD_LETTERED, outboxDeadLettered);
        Gauge.builder("ldapportal.events.outbox.oldest.pending.age.seconds", outboxOldestPendingEpochSec,
                        f -> MetricAges.liveSeconds(f.get()))
                .description("Age of the oldest PENDING outbox entry (delivery backlog); 0 when none")
                .baseUnit("seconds").register(registry);
        Gauge.builder("ldapportal.report.jobs.enabled", reportJobsEnabled, AtomicLong::doubleValue)
                .description("Enabled scheduled report jobs")
                .baseUnit("jobs").register(registry);
        Gauge.builder("ldapportal.report.jobs.failed", reportJobsFailed, AtomicLong::doubleValue)
                .description("Enabled scheduled report jobs whose last run failed")
                .baseUnit("jobs").register(registry);
    }

    private void outboxGauge(MeterRegistry registry, OutboxStatus status, AtomicLong value) {
        Gauge.builder("ldapportal.events.outbox", value, AtomicLong::doubleValue)
                .description("Event-outbox entries by delivery status")
                .tag("status", status.name().toLowerCase(Locale.ROOT))
                .baseUnit("entries").register(registry);
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
            outboxPending.set(outboxRepo.countByStatus(OutboxStatus.PENDING));
            outboxDelivering.set(outboxRepo.countByStatus(OutboxStatus.DELIVERING));
            outboxDeadLettered.set(outboxRepo.countByStatus(OutboxStatus.DEAD_LETTERED));
            Instant oldest = outboxRepo.findOldestCreatedAtByStatus(OutboxStatus.PENDING);
            outboxOldestPendingEpochSec.set(oldest == null ? 0L : oldest.getEpochSecond());

            reportJobsEnabled.set(reportJobRepo.countByEnabledTrue());
            reportJobsFailed.set(reportJobRepo.countByEnabledTrueAndLastRunStatus(ReportJobRunStatus.FAILED));
        } catch (RuntimeException e) {
            // Metrics refresh must never disrupt the app; keep the last snapshot.
            log.debug("Job health metrics refresh failed: {}", e.toString());
        }
    }
}
