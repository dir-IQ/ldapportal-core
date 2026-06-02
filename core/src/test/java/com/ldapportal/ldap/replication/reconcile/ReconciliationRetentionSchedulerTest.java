// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.repository.ReconciliationRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationRetentionSchedulerTest {

    @Mock private ReconciliationRunRepository runRepo;

    private ReconciliationRetentionScheduler scheduler(int retentionDays) {
        ReconciliationRetentionScheduler s = new ReconciliationRetentionScheduler(runRepo);
        ReflectionTestUtils.setField(s, "retentionDays", retentionDays);
        return s;
    }

    @Test
    void purge_deletesWithCutoffComputedFromConfiguredDays() {
        when(runRepo.deleteFinishedWithoutOpenFindingsBefore(any())).thenReturn(4);

        OffsetDateTime before = OffsetDateTime.now();
        scheduler(90).purge();
        OffsetDateTime after = OffsetDateTime.now();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        org.mockito.Mockito.verify(runRepo).deleteFinishedWithoutOpenFindingsBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(before.minusDays(90), after.minusDays(90));
    }

    @Test
    void purge_swallowsRepositoryFailure_soTheScheduledJobIsNotUnscheduled() {
        when(runRepo.deleteFinishedWithoutOpenFindingsBefore(any()))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThatCode(() -> scheduler(90).purge()).doesNotThrowAnyException();
    }
}
