// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
import com.ldapportal.core.reports.schedule.ReportJobRunStatus;
import com.ldapportal.repository.ScheduledReportJobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the outbox + report-job gauges snapshot the right repository counts,
 * including the per-status outbox breakdown and the live delivery-backlog age.
 */
class JobHealthMetricsTest {

    private OutboxEntryRepository outboxRepo;
    private ScheduledReportJobRepository reportJobRepo;
    private SimpleMeterRegistry registry;
    private JobHealthMetrics metrics;

    @BeforeEach
    void setUp() {
        outboxRepo = mock(OutboxEntryRepository.class);
        reportJobRepo = mock(ScheduledReportJobRepository.class);
        registry = new SimpleMeterRegistry();
        metrics = new JobHealthMetrics(outboxRepo, reportJobRepo, registry);
    }

    @Test
    void outbox_depth_breaks_down_by_status() {
        when(outboxRepo.countByStatus(OutboxStatus.PENDING)).thenReturn(10L);
        when(outboxRepo.countByStatus(OutboxStatus.DELIVERING)).thenReturn(2L);
        when(outboxRepo.countByStatus(OutboxStatus.DEAD_LETTERED)).thenReturn(1L);

        metrics.refresh();

        assertThat(outbox("pending")).isEqualTo(10.0);
        assertThat(outbox("delivering")).isEqualTo(2.0);
        assertThat(outbox("dead_lettered")).isEqualTo(1.0);
    }

    @Test
    void outbox_backlog_age_is_computed_from_oldest_pending() {
        when(outboxRepo.findOldestCreatedAtByStatus(OutboxStatus.PENDING))
                .thenReturn(Instant.now().minusSeconds(90));
        metrics.refresh();
        assertThat(registry.get("ldapportal.events.outbox.oldest.pending.age.seconds").gauge().value())
                .isBetween(89.0, 120.0);
    }

    @Test
    void no_pending_outbox_reports_zero_age() {
        when(outboxRepo.findOldestCreatedAtByStatus(OutboxStatus.PENDING)).thenReturn(null);
        metrics.refresh();
        assertThat(registry.get("ldapportal.events.outbox.oldest.pending.age.seconds").gauge().value()).isZero();
    }

    @Test
    void report_jobs_enabled_and_failed_counts() {
        when(reportJobRepo.countByEnabledTrue()).thenReturn(5L);
        when(reportJobRepo.countByEnabledTrueAndLastRunStatus(ReportJobRunStatus.FAILED)).thenReturn(1L);

        metrics.refresh();

        assertThat(registry.get("ldapportal.report.jobs.enabled").gauge().value()).isEqualTo(5.0);
        assertThat(registry.get("ldapportal.report.jobs.failed").gauge().value()).isEqualTo(1.0);
    }

    private double outbox(String status) {
        return registry.get("ldapportal.events.outbox").tag("status", status).gauge().value();
    }
}
