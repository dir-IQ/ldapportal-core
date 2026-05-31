// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.repository.ReplicationEventRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplicationEventRetentionSchedulerTest {

    @Mock private ReplicationEventRepository eventRepo;

    private ReplicationEventRetentionScheduler scheduler(int floorDays, int capDays) {
        ReplicationEventRetentionScheduler s = new ReplicationEventRetentionScheduler(eventRepo);
        ReflectionTestUtils.setField(s, "floorDays", floorDays);
        ReflectionTestUtils.setField(s, "capDays", capDays);
        return s;
    }

    @Test
    void purge_callsBothDeletesWithCutoffsComputedFromConfiguredDays() {
        when(eventRepo.deleteDeliveredForEnabledLinksOlderThan(any())).thenReturn(2);
        when(eventRepo.deleteEnqueuedBefore(any())).thenReturn(3);

        OffsetDateTime before = OffsetDateTime.now();
        scheduler(30, 90).purge();
        OffsetDateTime after = OffsetDateTime.now();

        ArgumentCaptor<OffsetDateTime> floorCutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> capCutoff   = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(eventRepo).deleteDeliveredForEnabledLinksOlderThan(floorCutoff.capture());
        verify(eventRepo).deleteEnqueuedBefore(capCutoff.capture());

        // Cutoffs are now − floor/cap days, bounded by the wall-clock window
        // spanning the call.
        assertThat(floorCutoff.getValue()).isBetween(before.minusDays(30), after.minusDays(30));
        assertThat(capCutoff.getValue()).isBetween(before.minusDays(90), after.minusDays(90));
    }

    @Test
    void purge_swallowsRepositoryFailure_soTheScheduledJobIsNotUnscheduled() {
        when(eventRepo.deleteDeliveredForEnabledLinksOlderThan(any()))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThatCode(() -> scheduler(30, 90).purge()).doesNotThrowAnyException();
        // First delete threw, so the second is not reached — and nothing escaped.
        verify(eventRepo, never()).deleteEnqueuedBefore(any());
    }
}
