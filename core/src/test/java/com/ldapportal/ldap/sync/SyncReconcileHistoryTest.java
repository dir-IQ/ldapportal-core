// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncReconcileRunResponse;
import com.ldapportal.entity.SyncReconcileRun;
import com.ldapportal.entity.enums.SyncReconcileRunStatus;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.repository.SyncReconcileRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncReconcileHistoryTest {

    private static final UUID SET = UUID.randomUUID();

    @Mock private SyncReconcileRunRepository runRepo;

    private SyncReconcileHistory history;

    @BeforeEach
    void setUp() {
        history = new SyncReconcileHistory(runRepo, 900_000);
    }

    @Test
    void start_writesARunningRow() {
        SyncReconcileHistory.Run run = history.start(SET, SyncReconcileTrigger.MANUAL, "alice");

        SyncReconcileRun row = run.row();
        assertThat(row.getId()).isNotNull();
        assertThat(row.getSyncSetId()).isEqualTo(SET);
        assertThat(row.getStatus()).isEqualTo(SyncReconcileRunStatus.RUNNING);
        assertThat(row.getRequestedBy()).isEqualTo("alice");
        verify(runRepo).save(row);
    }

    @Test
    void record_countsEachOutcome_andNullAsSkipped() {
        SyncReconcileHistory.Run run = history.start(SET, SyncReconcileTrigger.SCHEDULED, null);
        for (SyncPreviewAction a : SyncPreviewAction.values()) {
            history.record(run, a);
        }
        history.record(run, null);
        history.fault(run);

        SyncReconcileRun r = run.row();
        assertThat(List.of(r.getAdded(), r.getModified(), r.getMoved(), r.getDeleted(), r.getHeldForReview(),
                r.getNoChange(), r.getForgotten(), r.getUnchanged(), r.getFailed(), r.getSkipped(),
                r.getFaulted())).containsOnly(1);
        assertThat(r.getProcessed()).isEqualTo(SyncPreviewAction.values().length + 2);
    }

    @Test
    void record_flushesOnlyOnHeartbeat_notPerEntry() {
        SyncReconcileHistory.Run run = history.start(SET, SyncReconcileTrigger.SCHEDULED, null);
        for (int i = 0; i < 500; i++) {
            history.record(run, SyncPreviewAction.UNCHANGED);
        }
        verify(runRepo, times(1)).save(run.row()); // just the start; the heartbeat isn't due
    }

    @Test
    void complete_recordsManagedAfter_andPrunesOldRuns() {
        SyncReconcileHistory.Run run = history.start(SET, SyncReconcileTrigger.SCHEDULED, null);
        List<SyncReconcileRun> kept = new ArrayList<>();
        for (int i = 0; i < SyncReconcileHistory.KEEP_PER_SET; i++) {
            SyncReconcileRun r = new SyncReconcileRun();
            r.setStartedAt(OffsetDateTime.now().minusHours(i));
            kept.add(r);
        }
        when(runRepo.findBySyncSetIdOrderByStartedAtDesc(eq(SET), any())).thenReturn(kept);

        history.complete(run, 4741);

        assertThat(run.row().getStatus()).isEqualTo(SyncReconcileRunStatus.COMPLETE);
        assertThat(run.row().getManagedAfter()).isEqualTo(4741L);
        assertThat(run.row().getFinishedAt()).isNotNull();
        verify(runRepo).deleteStartedBefore(SET, kept.get(SyncReconcileHistory.KEEP_PER_SET - 1).getStartedAt());
    }

    @Test
    void complete_keepsEverything_whileUnderTheCap() {
        SyncReconcileHistory.Run run = history.start(SET, SyncReconcileTrigger.SCHEDULED, null);
        when(runRepo.findBySyncSetIdOrderByStartedAtDesc(eq(SET), any())).thenReturn(List.of(run.row()));

        history.complete(run, 1);

        verify(runRepo, never()).deleteStartedBefore(any(), any());
    }

    @Test
    void fail_recordsTheError() {
        SyncReconcileHistory.Run run = history.start(SET, SyncReconcileTrigger.SCHEDULED, null);

        history.fail(run, new IllegalStateException("index unavailable"));

        assertThat(run.row().getStatus()).isEqualTo(SyncReconcileRunStatus.FAILED);
        assertThat(run.row().getError()).isEqualTo("index unavailable");
        verify(runRepo, atLeastOnce()).save(run.row());
    }

    @Test
    void historyWriteFailures_neverFailTheReconcile() {
        when(runRepo.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> {
            SyncReconcileHistory.Run run = history.start(SET, SyncReconcileTrigger.SCHEDULED, null);
            history.planned(run, 10, 5, true, false, null);
            history.record(run, SyncPreviewAction.ADD);
            history.complete(run, 6);
        }).doesNotThrowAnyException();
    }

    @Test
    void toResponse_reportsAStaleRunningRunAsInterrupted() {
        OffsetDateTime now = OffsetDateTime.now();
        SyncReconcileRun r = new SyncReconcileRun();
        r.setId(UUID.randomUUID());
        r.setStatus(SyncReconcileRunStatus.RUNNING);
        r.setStartedAt(now.minusHours(2));
        r.setHeartbeatAt(now.minusHours(1));

        SyncReconcileRunResponse stale = SyncReconcileHistory.toResponse(r, now.minusMinutes(15));
        assertThat(stale.status()).isEqualTo(SyncReconcileRunStatus.FAILED);
        assertThat(stale.error()).isEqualTo(SyncReconcileHistory.INTERRUPTED);

        r.setHeartbeatAt(now);
        SyncReconcileRunResponse live = SyncReconcileHistory.toResponse(r, now.minusMinutes(15));
        assertThat(live.status()).isEqualTo(SyncReconcileRunStatus.RUNNING);
        assertThat(live.error()).isNull();
    }
}
