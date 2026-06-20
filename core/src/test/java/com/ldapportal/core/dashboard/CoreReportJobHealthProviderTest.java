// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.dashboard;

import com.ldapportal.core.reports.schedule.ReportJobRunStatus;
import com.ldapportal.repository.ScheduledReportJobRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoreReportJobHealthProviderTest {

    private final ScheduledReportJobRepository repo = mock(ScheduledReportJobRepository.class);
    private final CoreReportJobHealthProvider provider = new CoreReportJobHealthProvider(repo);

    @Test
    void health_reportsEnabledAndFailedCounts() {
        when(repo.countByEnabledTrue()).thenReturn(5L);
        when(repo.countByEnabledTrueAndLastRunStatus(ReportJobRunStatus.FAILED)).thenReturn(2L);

        ReportJobHealth health = provider.health();

        assertThat(health.enabled()).isEqualTo(5);
        assertThat(health.failed()).isEqualTo(2);
    }

    @Test
    void health_zeroWhenNoJobs() {
        when(repo.countByEnabledTrue()).thenReturn(0L);
        when(repo.countByEnabledTrueAndLastRunStatus(ReportJobRunStatus.FAILED)).thenReturn(0L);

        assertThat(provider.health()).isEqualTo(ReportJobHealth.empty());
    }
}
