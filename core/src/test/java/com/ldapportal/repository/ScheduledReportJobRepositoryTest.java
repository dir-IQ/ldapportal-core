// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.core.reports.schedule.ReportDeliveryMethod;
import com.ldapportal.core.reports.schedule.ReportJobRunStatus;
import com.ldapportal.core.reports.schedule.ReportOutputFormat;
import com.ldapportal.core.reports.schedule.ReportRunHistoryEntry;
import com.ldapportal.core.reports.schedule.ReportRunTrigger;
import com.ldapportal.entity.ScheduledReportJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence contract for {@link ScheduledReportJob}: the JSONB round-trip for
 * {@code reportParams} (a Map) and {@code runHistory} (a record list, including
 * an {@link OffsetDateTime} that needs the JavaTimeModule on the JSON mapper),
 * plus the directory-scoped finders and the dashboard count queries. Runs
 * against H2 in PostgreSQL mode (see application-test.yml).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ScheduledReportJobRepositoryTest {

    @Autowired private ScheduledReportJobRepository repository;
    @Autowired private TestEntityManager em;

    private ScheduledReportJob job(UUID directoryId, String name, boolean enabled) {
        ScheduledReportJob j = new ScheduledReportJob();
        j.setDirectoryId(directoryId);
        j.setName(name);
        j.setReportType("DISABLED_ACCOUNTS");
        j.setCronExpression("0 0 8 * * MON");
        j.setEnabled(enabled);
        return j;
    }

    @Test
    void persists_and_reads_back_jsonb_params_and_run_history() {
        UUID dir = UUID.randomUUID();
        OffsetDateTime runAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);

        ScheduledReportJob j = job(dir, "Weekly disabled", true);
        j.setReportParams(Map.of("lookbackDays", 30, "objectType", "USER"));
        j.setOutputFormat(ReportOutputFormat.CSV);
        j.setDeliveryMethod(ReportDeliveryMethod.EMAIL);
        j.setDeliveryRecipients("ops@example.com");
        j.setTimezone("America/New_York");
        j.setLastRunStatus(ReportJobRunStatus.SUCCESS);
        j.setRunHistory(List.of(new ReportRunHistoryEntry(
                runAt.minusSeconds(2), runAt, ReportJobRunStatus.SUCCESS, "delivered 12 rows",
                ReportRunTrigger.SCHEDULED)));

        UUID id = repository.saveAndFlush(j).getId();
        em.clear();

        ScheduledReportJob read = repository.findById(id).orElseThrow();
        assertThat(read.getDirectoryId()).isEqualTo(dir);
        assertThat(read.getReportType()).isEqualTo("DISABLED_ACCOUNTS");
        assertThat(read.getReportParams()).containsEntry("objectType", "USER");
        assertThat(read.getOutputFormat()).isEqualTo(ReportOutputFormat.CSV);
        assertThat(read.getDeliveryMethod()).isEqualTo(ReportDeliveryMethod.EMAIL);
        assertThat(read.getTimezone()).isEqualTo("America/New_York");
        assertThat(read.getCreatedAt()).isNotNull();
        assertThat(read.getRunHistory()).singleElement().satisfies(e -> {
            assertThat(e.status()).isEqualTo(ReportJobRunStatus.SUCCESS);
            assertThat(e.message()).isEqualTo("delivered 12 rows");
            assertThat(e.runAt().toInstant()).isEqualTo(runAt.toInstant());
            assertThat(e.startedAt().toInstant()).isEqualTo(runAt.minusSeconds(2).toInstant());
            assertThat(e.trigger()).isEqualTo(ReportRunTrigger.SCHEDULED);
        });
    }

    @Test
    void run_history_defaults_to_empty_not_null() {
        UUID id = repository.saveAndFlush(job(UUID.randomUUID(), "no history", true)).getId();
        em.clear();
        assertThat(repository.findById(id).orElseThrow().getRunHistory()).isEmpty();
    }

    @Test
    void findByIdAndDirectoryId_is_directory_scoped() {
        UUID dir = UUID.randomUUID();
        UUID id = repository.saveAndFlush(job(dir, "scoped", true)).getId();
        em.clear();

        assertThat(repository.findByIdAndDirectoryId(id, dir)).isPresent();
        assertThat(repository.findByIdAndDirectoryId(id, UUID.randomUUID())).isEmpty();
    }

    @Test
    void findAllByEnabledTrue_and_counts() {
        UUID dir = UUID.randomUUID();
        repository.save(job(dir, "on-1", true));
        ScheduledReportJob failed = job(dir, "on-2-failed", true);
        failed.setLastRunStatus(ReportJobRunStatus.FAILED);
        repository.save(failed);
        repository.save(job(dir, "off", false));
        repository.flush();
        em.clear();

        assertThat(repository.findAllByEnabledTrue()).extracting(ScheduledReportJob::getName)
                .containsExactlyInAnyOrder("on-1", "on-2-failed");
        assertThat(repository.countByEnabledTrue()).isEqualTo(2);
        assertThat(repository.countByEnabledTrueAndLastRunStatus(ReportJobRunStatus.FAILED)).isEqualTo(1);
    }

    @Test
    void findAllByDirectoryId_paged_and_scoped() {
        UUID dirA = UUID.randomUUID();
        UUID dirB = UUID.randomUUID();
        repository.save(job(dirA, "a1", true));
        repository.save(job(dirA, "a2", true));
        repository.save(job(dirB, "b1", true));
        repository.flush();

        assertThat(repository.findAllByDirectoryId(dirA, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(2);
        assertThat(repository.findAllByDirectoryId(dirB, PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
    }
}
