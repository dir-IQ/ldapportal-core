// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports.schedule;

import com.ldapportal.entity.ScheduledReportJob;
import com.ldapportal.repository.ScheduledReportJobRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledReportJobSchedulerTest {

    private final ScheduledReportJobRepository jobRepo = mock(ScheduledReportJobRepository.class);
    private final ScheduledReportJobService jobService = mock(ScheduledReportJobService.class);

    private ScheduledReportJobScheduler schedulerAt(String instant) {
        return new ScheduledReportJobScheduler(jobRepo, jobService,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private ScheduledReportJob job(String cron, String timezone, OffsetDateTime lastRun) {
        ScheduledReportJob j = new ScheduledReportJob();
        j.setId(UUID.randomUUID());
        j.setName("job");
        j.setCronExpression(cron);
        j.setTimezone(timezone);
        j.setLastRunAt(lastRun);
        return j;
    }

    @Test
    void isDue_trueWhenNeverRun() {
        ScheduledReportJob j = job("0 0 8 * * *", null, null);
        assertThat(schedulerAt("2026-06-20T00:00:00Z").isDue(j)).isTrue();
    }

    @Test
    void isDue_respectsCronSinceLastRun_utc() {
        // last ran at 08:00 on the 20th; next fire is 08:00 on the 21st.
        ScheduledReportJob j = job("0 0 8 * * *", null, OffsetDateTime.parse("2026-06-20T08:00:00Z"));
        assertThat(schedulerAt("2026-06-20T09:00:00Z").isDue(j)).isFalse(); // before next fire
        assertThat(schedulerAt("2026-06-21T08:30:00Z").isDue(j)).isTrue();  // after next fire
    }

    @Test
    void isDue_evaluatesCronInJobTimezone() {
        // 8am daily in New York (UTC-4 in June). Last ran 20th 08:00 local (12:00Z).
        ScheduledReportJob j = job("0 0 8 * * *", "America/New_York",
                OffsetDateTime.parse("2026-06-20T12:00:00Z"));
        // 21st 13:00Z == 09:00 NY, past the 08:00 NY fire → due.
        assertThat(schedulerAt("2026-06-21T13:00:00Z").isDue(j)).isTrue();
        // 21st 11:00Z == 07:00 NY, before the 08:00 NY fire → not due.
        assertThat(schedulerAt("2026-06-21T11:00:00Z").isDue(j)).isFalse();
    }

    @Test
    void isDue_falseAndDoesNotThrowOnBadCron() {
        ScheduledReportJob j = job("nonsense", null, null);
        assertThat(schedulerAt("2026-06-20T00:00:00Z").isDue(j)).isFalse();
    }

    @Test
    void poll_runsOnlyDueJobs() {
        ScheduledReportJob due = job("0 0 8 * * *", null, null); // never run → due
        ScheduledReportJob notDue = job("0 0 8 * * *", null, OffsetDateTime.parse("2026-06-20T08:00:00Z"));
        when(jobRepo.findAllByEnabledTrue()).thenReturn(List.of(due, notDue));

        schedulerAt("2026-06-20T09:00:00Z").pollReportJobs();

        verify(jobService).runJob(due);
        verify(jobService, org.mockito.Mockito.never()).runJob(notDue);
    }
}
