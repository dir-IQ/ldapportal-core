// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncReconcileRunDetail;
import com.ldapportal.dto.sync.SyncReconcileRunResponse;
import com.ldapportal.dto.sync.SyncRunFailureReason;
import com.ldapportal.dto.sync.SyncRunSettings;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private static final int KEEP = 20;

    private static RecomputeOutcome outcome(SyncPreviewAction action, String dn, String reason) {
        return new RecomputeOutcome(action, dn, reason);
    }

    @Mock private SyncReconcileRunRepository runRepo;

    private SyncReconcileHistory history;

    @BeforeEach
    void setUp() {
        history = new SyncReconcileHistory(runRepo, new ObjectMapper().findAndRegisterModules(), 900_000, KEEP);
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
            history.record(run, new RecomputeOutcome(a, "cn=" + a + ",dc=dst", "apply failed: OTHER"));
        }
        history.record(run, null);
        history.fault(run, new IllegalStateException("contended"));

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
            history.record(run, outcome(SyncPreviewAction.UNCHANGED, null, null));
        }
        verify(runRepo, times(1)).save(run.row()); // just the start; the heartbeat isn't due
    }

    @Test
    void complete_recordsManagedAfter_andPrunesOldRuns() {
        SyncReconcileHistory.Run run = history.start(SET, SyncReconcileTrigger.SCHEDULED, null);
        List<SyncReconcileRun> kept = new ArrayList<>();
        for (int i = 0; i < KEEP; i++) {
            SyncReconcileRun r = new SyncReconcileRun();
            r.setStartedAt(OffsetDateTime.now().minusHours(i));
            kept.add(r);
        }
        when(runRepo.findBySyncSetIdOrderByStartedAtDesc(eq(SET), any())).thenReturn(kept);

        history.complete(run, 4741);

        assertThat(run.row().getStatus()).isEqualTo(SyncReconcileRunStatus.COMPLETE);
        assertThat(run.row().getManagedAfter()).isEqualTo(4741L);
        assertThat(run.row().getFinishedAt()).isNotNull();
        verify(runRepo).deleteStartedBefore(SET, kept.get(KEEP - 1).getStartedAt());
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
            history.planned(run, 10, 5, true, false, null, null);
            history.record(run, outcome(SyncPreviewAction.ADD, "cn=a,dc=dst", null));
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

    @Test
    void start_recordsTheInstance_andPlannedRecordsTimingAndSettings() {
        SyncReconcileHistory.Run run = history.start(SET, SyncReconcileTrigger.SCHEDULED, null);
        SyncRunSettings settings = new SyncRunSettings("REVIEW", "ou=people,dc=src", "SUB", "(objectClass=*)",
                "ou=Users,dc=dst", null, null, 3600L, 50, 20, 20);

        history.planned(run, 4741, 876, true, false, null, settings);

        assertThat(run.row().getInstanceName()).isNotBlank();
        assertThat(run.row().getPlannedAt()).isNotNull();
        assertThat(run.row().getSettings()).contains("\"deletePolicy\":\"REVIEW\"");
    }

    @Test
    void samples_keepTheFirstDnsPerOutcome_upToTheLimit_butNotUnchanged() {
        SyncReconcileHistory.Run run = history.start(SET, SyncReconcileTrigger.SCHEDULED, null);
        for (int i = 0; i < SyncReconcileHistory.SAMPLE_LIMIT + 5; i++) {
            history.record(run, outcome(SyncPreviewAction.ADD, "uid=u" + i + ",dc=dst", null));
        }
        history.record(run, outcome(SyncPreviewAction.UNCHANGED, "uid=same,dc=dst", null));
        history.complete(run, 1);

        when(runRepo.findById(run.row().getId())).thenReturn(java.util.Optional.of(run.row()));
        SyncReconcileRunDetail detail = history.detail(SET, run.row().getId());

        assertThat(detail.samples().get("ADD")).hasSize(SyncReconcileHistory.SAMPLE_LIMIT)
                .startsWith("uid=u0,dc=dst");
        assertThat(detail.samples()).doesNotContainKey("UNCHANGED");
        assertThat(detail.run().added()).isEqualTo(SyncReconcileHistory.SAMPLE_LIMIT + 5);
    }

    @Test
    void failures_areGroupedByReason_mostFrequentFirst_withAnExample() {
        SyncReconcileHistory.Run run = history.start(SET, SyncReconcileTrigger.SCHEDULED, null);
        history.record(run, outcome(SyncPreviewAction.ERROR, "uid=a,dc=dst",
                "apply failed: NO_SUCH_OBJECT — parent of 'uid=a,dc=dst' does not exist"));
        history.record(run, outcome(SyncPreviewAction.ERROR, "uid=b,dc=dst",
                "apply failed: NO_SUCH_OBJECT — parent of 'uid=b,dc=dst' does not exist"));
        history.record(run, outcome(SyncPreviewAction.ERROR, "uid=c,dc=dst",
                "apply failed: OBJECT_CLASS_VIOLATION — missing 'sn'"));
        history.fault(run, new IllegalStateException("contended"));
        history.complete(run, 1);

        when(runRepo.findById(run.row().getId())).thenReturn(java.util.Optional.of(run.row()));
        List<SyncRunFailureReason> reasons = history.detail(SET, run.row().getId()).failureReasons();

        assertThat(reasons).extracting(SyncRunFailureReason::reason).containsExactly(
                "apply failed: NO_SUCH_OBJECT", "apply failed: OBJECT_CLASS_VIOLATION",
                "recompute error: IllegalStateException");
        assertThat(reasons.get(0).count()).isEqualTo(2);
        assertThat(reasons.get(0).example()).contains("uid=a,dc=dst");
    }

    @Test
    void detail_ofAnotherSetsRun_isNotFound() {
        SyncReconcileRun other = new SyncReconcileRun();
        other.setId(UUID.randomUUID());
        other.setSyncSetId(UUID.randomUUID());
        when(runRepo.findById(other.getId())).thenReturn(java.util.Optional.of(other));

        assertThatThrownBy(() -> history.detail(SET, other.getId()))
                .isInstanceOf(com.ldapportal.exception.ResourceNotFoundException.class);
    }

    @Test
    void recordDrift_attachesToTheLatestFinishedRun_once() {
        SyncReconcileRun latest = new SyncReconcileRun();
        latest.setFinishedAt(OffsetDateTime.now());
        when(runRepo.findBySyncSetIdOrderByStartedAtDesc(eq(SET), any())).thenReturn(List.of(latest));

        history.recordDrift(SET, 1, 2, 3);
        history.recordDrift(SET, 9, 9, 9); // already verified: not overwritten

        assertThat(latest.getVerifiedAt()).isNotNull();
        assertThat(List.of(latest.getVerifyMissing(), latest.getVerifyOrphan(), latest.getVerifyMismatch()))
                .containsExactly(1, 2, 3);
        verify(runRepo, times(1)).save(latest);
    }

    @Test
    void retention_isConfigurable() {
        SyncReconcileHistory keepTwo = new SyncReconcileHistory(runRepo, new ObjectMapper(), 900_000, 2);
        SyncReconcileHistory.Run run = keepTwo.start(SET, SyncReconcileTrigger.SCHEDULED, null);
        SyncReconcileRun older = new SyncReconcileRun();
        older.setStartedAt(OffsetDateTime.now().minusHours(1));
        when(runRepo.findBySyncSetIdOrderByStartedAtDesc(eq(SET), any())).thenReturn(List.of(run.row(), older));

        keepTwo.complete(run, 1);

        verify(runRepo).deleteStartedBefore(SET, older.getStartedAt());
    }
}
