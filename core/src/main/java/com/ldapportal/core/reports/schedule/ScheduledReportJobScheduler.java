// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.entity.ScheduledReportJob;
import com.ldapportal.repository.ScheduledReportJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls enabled scheduled-report jobs and runs the ones that are due, following
 * {@code OutboundDispatcherScheduler}'s DB-poll shape. Due-ness is computed in
 * Java from {@code cron_expression} + {@code last_run_at} (no {@code next_run_at}
 * column), evaluated in the job's timezone (null = UTC) — parity with the
 * commercial scheduler.
 *
 * <p>An in-memory {@code inProgress} set prevents a long run from overlapping
 * with the next poll. Execution + result recording live in
 * {@link ScheduledReportJobService#runJob(ScheduledReportJob)}, which never
 * throws, so one bad job can't break the loop.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ScheduledReportJobScheduler {

    private final ScheduledReportJobRepository jobRepo;
    private final ScheduledReportJobService jobService;
    private final Clock clock;

    private final Set<UUID> inProgress = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "${ldapportal.report.poll-interval-ms:60000}",
            initialDelayString = "${ldapportal.report.poll-initial-delay-ms:30000}")
    public void pollReportJobs() {
        for (ScheduledReportJob job : jobRepo.findAllByEnabledTrue()) {
            try {
                if (isDue(job)) {
                    run(job);
                }
            } catch (RuntimeException e) {
                log.error("Report poll loop error for job {} ({}): {}",
                        job.getId(), job.getName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Fire-and-forget run-now for the controller: runs the job off the request
     * thread (report execution + delivery can be slow) through the same
     * {@link #run(ScheduledReportJob)} path, so the {@code inProgress} guard still
     * de-duplicates against a concurrent poll.
     */
    @Async
    public void runNowAsync(ScheduledReportJob job) {
        run(job);
    }

    /** Runs the job now if not already in flight (used by the poll loop and run-now). */
    public void run(ScheduledReportJob job) {
        if (!inProgress.add(job.getId())) {
            log.warn("Report job '{}' ({}) is already running — skipping", job.getName(), job.getId());
            return;
        }
        try {
            jobService.runJob(job);
        } finally {
            inProgress.remove(job.getId());
        }
    }

    /**
     * Whether {@code job} is due: the next cron fire time after its last run (or
     * immediately if never run) is at or before "now" in the job's zone. A bad
     * cron logs and returns {@code false} rather than throwing.
     */
    boolean isDue(ScheduledReportJob job) {
        try {
            CronExpression cron = CronExpression.parse(job.getCronExpression());
            ZoneId zone = (job.getTimezone() != null && !job.getTimezone().isBlank())
                    ? ZoneId.of(job.getTimezone()) : ZoneOffset.UTC;
            LocalDateTime now = LocalDateTime.now(clock.withZone(zone));

            OffsetDateTime lastRun = job.getLastRunAt();
            if (lastRun == null) {
                return true; // never run — due on the next poll
            }
            LocalDateTime lastRunLocal = lastRun.atZoneSameInstant(zone).toLocalDateTime();
            LocalDateTime next = cron.next(lastRunLocal);
            return next != null && !next.isAfter(now);
        } catch (RuntimeException e) {
            log.warn("Invalid cron '{}' for report job '{}' ({}): {}",
                    job.getCronExpression(), job.getName(), job.getId(), e.getMessage());
            return false;
        }
    }
}
