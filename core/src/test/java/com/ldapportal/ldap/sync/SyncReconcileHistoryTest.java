// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncReconcileRunDetail;
import com.ldapportal.dto.sync.SyncReconcileRunResponse;
import com.ldapportal.dto.sync.SyncRunFailureReason;
import com.ldapportal.dto.sync.SyncRunSettings;
import com.ldapportal.entity.SyncReconcileRun;
import com.ldapportal.entity.SyncSet;
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
    private static final int KEEP_INCREMENTAL = 50;
    private static final UUID TARGET_DIR = UUID.randomUUID();

    private static SyncSet set(boolean recordAllDns) {
        SyncSet s = new SyncSet();
        s.setId(SET);
        s.setRecordAllDns(recordAllDns);
        return s;
    }

    private static RecomputeOutcome outcome(SyncPreviewAction action, String dn, String reason) {
        return new RecomputeOutcome(action, dn, reason);
    }

    @Mock private SyncReconcileRunRepository runRepo;
    @Mock private SyncRunEntryStore entryStore;
    @Mock private SyncWriteAuditor auditor;

    private SyncReconcileHistory history;

    @BeforeEach
    void setUp() {
        history = new SyncReconcileHistory(runRepo, entryStore, auditor,
                new ObjectMapper().findAndRegisterModules(), 900_000, KEEP, KEEP_INCREMENTAL);
    }

    @Test
    void start_writesARunningRow() {
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.MANUAL, "alice");

        SyncReconcileRun row = run.row();
        assertThat(row.getId()).isNotNull();
        assertThat(row.getSyncSetId()).isEqualTo(SET);
        assertThat(row.getStatus()).isEqualTo(SyncReconcileRunStatus.RUNNING);
        assertThat(row.getRequestedBy()).isEqualTo("alice");
        verify(runRepo).save(row);
    }

    @Test
    void record_countsEachOutcome_andNullAsSkipped() {
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);
        for (SyncPreviewAction a : SyncPreviewAction.values()) {
            history.record(run, new RecomputeOutcome(a, "cn=" + a + ",dc=dst", "apply failed: OTHER"));
        }
        history.record(run, null);
        history.fault(run, new IllegalStateException("contended"), "uid=x,dc=src");

        SyncReconcileRun r = run.row();
        assertThat(List.of(r.getAdded(), r.getModified(), r.getMoved(), r.getDeleted(), r.getHeldForReview(),
                r.getNoChange(), r.getForgotten(), r.getUnchanged(), r.getFailed(), r.getSkipped(),
                r.getFaulted())).containsOnly(1);
        assertThat(r.getProcessed()).isEqualTo(SyncPreviewAction.values().length + 2);
    }

    @Test
    void record_flushesOnlyOnHeartbeat_notPerEntry() {
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);
        for (int i = 0; i < 500; i++) {
            history.record(run, outcome(SyncPreviewAction.UNCHANGED, null, null));
        }
        verify(runRepo, times(1)).save(run.row()); // just the start; the heartbeat isn't due
    }

    @Test
    void complete_recordsManagedAfter_andPrunesOldRuns() {
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);
        List<SyncReconcileRun> kept = new ArrayList<>();
        for (int i = 0; i < KEEP; i++) {
            SyncReconcileRun r = new SyncReconcileRun();
            r.setStartedAt(OffsetDateTime.now().minusHours(i));
            kept.add(r);
        }
        when(runRepo.findBySyncSetIdAndTriggerInOrderByStartedAtDesc(eq(SET), any(), any())).thenReturn(kept);

        history.complete(run, 4741);

        assertThat(run.row().getStatus()).isEqualTo(SyncReconcileRunStatus.COMPLETE);
        assertThat(run.row().getManagedAfter()).isEqualTo(4741L);
        assertThat(run.row().getFinishedAt()).isNotNull();
        verify(runRepo).deleteStartedBefore(SET, SyncReconcileTrigger.RECONCILES, kept.get(KEEP - 1).getStartedAt());
    }

    @Test
    void complete_keepsEverything_whileUnderTheCap() {
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);
        when(runRepo.findBySyncSetIdAndTriggerInOrderByStartedAtDesc(eq(SET), any(), any())).thenReturn(List.of(run.row()));

        history.complete(run, 1);

        verify(runRepo, never()).deleteStartedBefore(any(), any(), any());
    }

    @Test
    void fail_recordsTheError() {
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);

        history.fail(run, new IllegalStateException("index unavailable"));

        assertThat(run.row().getStatus()).isEqualTo(SyncReconcileRunStatus.FAILED);
        assertThat(run.row().getError()).isEqualTo("index unavailable");
        verify(runRepo, atLeastOnce()).save(run.row());
    }

    @Test
    void historyWriteFailures_neverFailTheReconcile() {
        when(runRepo.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> {
            SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);
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
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);
        SyncRunSettings settings = new SyncRunSettings("REVIEW", "ou=people,dc=src", "SUB", "(objectClass=*)",
                "ou=Users,dc=dst", null, null, 3600L, 50, 20, 20);

        history.planned(run, 4741, 876, true, false, null, settings);

        assertThat(run.row().getInstanceName()).isNotBlank();
        assertThat(run.row().getPlannedAt()).isNotNull();
        assertThat(run.row().getSettings()).contains("\"deletePolicy\":\"REVIEW\"");
    }

    @Test
    void samples_keepTheFirstDnsPerOutcome_upToTheLimit_butNotUnchanged() {
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);
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
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);
        history.record(run, outcome(SyncPreviewAction.ERROR, "uid=a,dc=dst",
                "apply failed: NO_SUCH_OBJECT — parent of 'uid=a,dc=dst' does not exist"));
        history.record(run, outcome(SyncPreviewAction.ERROR, "uid=b,dc=dst",
                "apply failed: NO_SUCH_OBJECT — parent of 'uid=b,dc=dst' does not exist"));
        history.record(run, outcome(SyncPreviewAction.ERROR, "uid=c,dc=dst",
                "apply failed: OBJECT_CLASS_VIOLATION — missing 'sn'"));
        history.fault(run, new IllegalStateException("contended"), "uid=x,dc=src");
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
        when(runRepo.findBySyncSetIdAndTriggerInOrderByStartedAtDesc(eq(SET), any(), any())).thenReturn(List.of(latest));

        history.recordDrift(SET, 1, 2, 3);
        history.recordDrift(SET, 9, 9, 9); // already verified: not overwritten

        assertThat(latest.getVerifiedAt()).isNotNull();
        assertThat(List.of(latest.getVerifyMissing(), latest.getVerifyOrphan(), latest.getVerifyMismatch()))
                .containsExactly(1, 2, 3);
        verify(runRepo, times(1)).save(latest);
    }

    @Test
    void retention_isConfigurable() {
        SyncReconcileHistory keepTwo = new SyncReconcileHistory(runRepo, entryStore, auditor, new ObjectMapper(),
                900_000, 2, KEEP_INCREMENTAL);
        SyncReconcileHistory.Run run = keepTwo.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);
        SyncReconcileRun older = new SyncReconcileRun();
        older.setStartedAt(OffsetDateTime.now().minusHours(1));
        when(runRepo.findBySyncSetIdAndTriggerInOrderByStartedAtDesc(eq(SET), any(), any())).thenReturn(List.of(run.row(), older));

        keepTwo.complete(run, 1);

        verify(runRepo).deleteStartedBefore(SET, SyncReconcileTrigger.RECONCILES, older.getStartedAt());
    }

    // ── full DN list ─────────────────────────────────────────────────────────

    @Test
    void recordAllDns_keepsEveryChangedOrFlaggedEntry_inOrder_beyondTheSamples() {
        SyncReconcileHistory.Run run = history.start(set(true), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);
        int many = SyncReconcileHistory.SAMPLE_LIMIT + 10;
        for (int i = 0; i < many; i++) {
            history.record(run, outcome(SyncPreviewAction.MODIFY, "uid=u" + i + ",dc=dst", null));
        }
        history.record(run, outcome(SyncPreviewAction.UNCHANGED, "uid=same,dc=dst", null));
        history.record(run, outcome(SyncPreviewAction.ERROR, "uid=bad,dc=dst", "apply failed: OTHER"));
        history.fault(run, new IllegalStateException("contended"), "uid=src,dc=src");
        history.complete(run, 1);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<SyncRunEntryStore.Entry>> batch =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(entryStore, atLeastOnce()).append(eq(run.id()), batch.capture());
        List<SyncRunEntryStore.Entry> all = batch.getAllValues().stream().flatMap(List::stream).toList();
        assertThat(all).hasSize(many + 2);
        assertThat(all).extracting(SyncRunEntryStore.Entry::seq)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, many + 2).boxed().toList());
        assertThat(all.get(0)).isEqualTo(new SyncRunEntryStore.Entry(0, "MODIFY", "uid=u0,dc=dst", null));
        assertThat(all.get(many)).isEqualTo(
                new SyncRunEntryStore.Entry(many, "ERROR", "uid=bad,dc=dst", "apply failed: OTHER"));
        assertThat(all.get(many + 1).outcome()).isEqualTo(SyncReconcileHistory.FAULT);
        assertThat(all.get(many + 1).targetDn()).isEqualTo("uid=src,dc=src");
        assertThat(run.row().isAllDnsRecorded()).isTrue();
    }

    @Test
    void withoutRecordAllDns_onlySamplesAreKept() {
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);
        history.record(run, outcome(SyncPreviewAction.ADD, "uid=a,dc=dst", null));
        history.complete(run, 1);

        verify(entryStore, never()).append(any(), any());
        assertThat(run.row().isAllDnsRecorded()).isFalse();
    }

    @Test
    void entries_ofARunThatKeptOnlySamples_isRefused() {
        SyncReconcileRun r = new SyncReconcileRun();
        r.setId(UUID.randomUUID());
        r.setSyncSetId(SET);
        when(runRepo.findById(r.getId())).thenReturn(java.util.Optional.of(r));

        assertThatThrownBy(() -> history.entries(SET, r.getId(), e -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        verify(entryStore, never()).forEach(any(), any());

        r.setAllDnsRecorded(true);
        history.entries(SET, r.getId(), e -> { });
        verify(entryStore).forEach(eq(r.getId()), any());
    }

    // ── audit ────────────────────────────────────────────────────────────────

    @Test
    void record_auditsEachOutcome_againstTheRunsTargetAndId() {
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.MANUAL, "alice");
        RecomputeOutcome add = outcome(SyncPreviewAction.ADD, "uid=a,dc=dst", null);

        history.record(run, add);

        verify(auditor).audit(TARGET_DIR, SET, run.id(), SyncReconcileTrigger.MANUAL, add);
    }

    @Test
    void auditFailures_neverFailTheRun() {
        org.mockito.Mockito.doThrow(new RuntimeException("audit down")).when(auditor)
                .audit(any(), any(), any(), any(), any());
        SyncReconcileHistory.Run run = history.start(set(false), TARGET_DIR, SyncReconcileTrigger.SCHEDULED, null);

        assertThatCode(() -> history.record(run, outcome(SyncPreviewAction.ADD, "uid=a,dc=dst", null)))
                .doesNotThrowAnyException();
        assertThat(run.row().getAdded()).isEqualTo(1);
    }

    // ── incremental passes ───────────────────────────────────────────────────

    @Test
    void incrementalPass_thatChangedNothing_isNotRecorded() {
        SyncReconcileHistory.Run run = history.startIncremental(set(true), TARGET_DIR);
        history.record(run, outcome(SyncPreviewAction.UNCHANGED, "uid=a,dc=dst", null));
        history.record(run, outcome(SyncPreviewAction.NO_CHANGE, "uid=b,dc=dst", null));
        history.record(run, null);

        assertThat(history.finishIncremental(run, 10)).isFalse();
        verify(runRepo, never()).save(any());
        verify(entryStore, never()).append(any(), any());
    }

    @Test
    void incrementalPass_thatWrote_isRecordedAsACompletedIncrementalRun_andPrunedSeparately() {
        SyncReconcileHistory.Run run = history.startIncremental(set(true), TARGET_DIR);
        history.record(run, outcome(SyncPreviewAction.UNCHANGED, "uid=a,dc=dst", null));
        verify(runRepo, never()).save(any()); // held in memory until the pass ends
        history.record(run, outcome(SyncPreviewAction.MODIFY, "uid=b,dc=dst", null));

        assertThat(history.finishIncremental(run, 10)).isTrue();

        SyncReconcileRun r = run.row();
        assertThat(r.getTrigger()).isEqualTo(SyncReconcileTrigger.INCREMENTAL);
        assertThat(r.getStatus()).isEqualTo(SyncReconcileRunStatus.COMPLETE);
        assertThat(List.of(r.getProcessed(), r.getModified(), r.getUnchanged())).containsExactly(2, 1, 1);
        assertThat(r.getManagedAfter()).isEqualTo(10L);
        verify(runRepo).save(r);
        verify(entryStore).append(run.id(),
                List.of(new SyncRunEntryStore.Entry(0, "MODIFY", "uid=b,dc=dst", null)));
        verify(runRepo).findBySyncSetIdAndTriggerInOrderByStartedAtDesc(eq(SET),
                eq(java.util.EnumSet.of(SyncReconcileTrigger.INCREMENTAL)),
                eq(org.springframework.data.domain.PageRequest.of(0, KEEP_INCREMENTAL)));
    }

    @Test
    void incrementalPass_auditsItsWrites_evenBeforeItIsSaved() {
        SyncReconcileHistory.Run run = history.startIncremental(set(false), TARGET_DIR);
        RecomputeOutcome delete = outcome(SyncPreviewAction.DELETE, "uid=gone,dc=dst", null);

        history.record(run, delete);

        verify(auditor).audit(TARGET_DIR, SET, run.id(), SyncReconcileTrigger.INCREMENTAL, delete);
    }

    @Test
    void recent_filtersByKind() {
        history.recent(SET, 10, SyncReconcileHistory.RunKind.RECONCILE);
        verify(runRepo).findBySyncSetIdAndTriggerInOrderByStartedAtDesc(eq(SET),
                eq(SyncReconcileTrigger.RECONCILES), any());

        history.recent(SET, 10, null);
        verify(runRepo).findBySyncSetIdOrderByStartedAtDesc(eq(SET), any());
    }
}
