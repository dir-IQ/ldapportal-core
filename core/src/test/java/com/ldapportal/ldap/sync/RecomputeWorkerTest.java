// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.RecomputeRequestRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Orchestration unit tests for {@link RecomputeWorker}: the entitlement gate and
 * the claim/settle lease (process + delete-if-claimed on success; release-claim
 * on unexpected fault; skip when the claim race is lost).
 */
@ExtendWith(MockitoExtension.class)
class RecomputeWorkerTest {

    @Mock private RecomputeRequestRepository requestRepo;
    @Mock private RecomputeEngine engine;
    @Mock private EntitlementService entitlementService;
    @Mock private SyncSetRepository syncSetRepo;
    @Mock private SyncLinkRepository syncLinkRepo;
    @Mock private MembershipRepository membershipRepo;
    @Mock private SyncReconcileHistory history;
    @InjectMocks private RecomputeWorker worker;

    private static final UUID SET = UUID.randomUUID();

    private RecomputeRequest req(String key) {
        RecomputeRequest r = new RecomputeRequest();
        r.setSyncSetId(SET);
        r.setRequestKey(key);
        return r;
    }

    @Test
    void drain_gatedOff_doesNothing() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(false);
        worker.drainQueue();
        verifyNoInteractions(requestRepo, engine);
    }

    @Test
    void drain_claimsProcessesAndSettles() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(true);
        when(requestRepo.findBatchUnclaimed(any())).thenReturn(List.of(req("k")));
        when(requestRepo.claim(eq(SET), eq("k"), any())).thenReturn(1);

        worker.drainQueue();

        verify(engine).process(SET, "k", false);
        verify(requestRepo).deleteIfClaimed(SET, "k");
        verify(requestRepo, never()).releaseClaim(any(), any());
    }

    @Test
    void drain_lostClaimRace_skipsProcessing() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(true);
        when(requestRepo.findBatchUnclaimed(any())).thenReturn(List.of(req("k")));
        when(requestRepo.claim(eq(SET), eq("k"), any())).thenReturn(0);

        worker.drainQueue();

        verify(engine, never()).process(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(requestRepo, never()).deleteIfClaimed(any(), any());
    }

    @Test
    void drain_unexpectedFault_releasesClaimForRetry() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(true);
        when(requestRepo.findBatchUnclaimed(any())).thenReturn(List.of(req("k")));
        when(requestRepo.claim(eq(SET), eq("k"), any())).thenReturn(1);
        doThrow(new RuntimeException("boom")).when(engine).process(SET, "k", false);

        worker.drainQueue();

        verify(requestRepo).releaseClaim(SET, "k");
        verify(requestRepo, never()).deleteIfClaimed(any(), any());
    }

    @Test
    void drain_recordsOneIncrementalRunPerSet_withEachOutcome() {
        UUID link = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(SET);
        set.setLinkId(link);
        SyncLink syncLink = new SyncLink();
        syncLink.setTargetDirId(target);
        // Run is final: take a real (deferred, unsaved) one from a real history.
        SyncReconcileHistory.Run run = new SyncReconcileHistory(
                org.mockito.Mockito.mock(com.ldapportal.repository.SyncReconcileRunRepository.class),
                org.mockito.Mockito.mock(SyncRunEntryStore.class), org.mockito.Mockito.mock(SyncWriteAuditor.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), 900_000, 20, 50).startIncremental(set, target);
        RecomputeOutcome modify = new RecomputeOutcome(SyncPreviewAction.MODIFY, "uid=a,dc=dst", null);
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(true);
        when(requestRepo.findBatchUnclaimed(any())).thenReturn(List.of(req("a"), req("b")));
        when(requestRepo.claim(eq(SET), any(), any())).thenReturn(1);
        when(syncSetRepo.findById(SET)).thenReturn(Optional.of(set));
        when(syncLinkRepo.findById(link)).thenReturn(Optional.of(syncLink));
        when(history.startIncremental(set, target)).thenReturn(run);
        when(engine.process(SET, "a", false)).thenReturn(modify);
        doThrow(new IllegalStateException("contended")).when(engine).process(SET, "b", false);
        when(membershipRepo.countBySyncSetId(SET)).thenReturn(7L);

        worker.drainQueue();

        verify(history, org.mockito.Mockito.times(1)).startIncremental(set, target);
        verify(history).record(run, modify);
        verify(history).fault(eq(run), any(IllegalStateException.class), eq("b"));
        verify(history).finishIncremental(run, 7L);
    }

    @Test
    void drain_stillProcesses_whenTheRunCantBeStarted() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(true);
        when(requestRepo.findBatchUnclaimed(any())).thenReturn(List.of(req("k")));
        when(requestRepo.claim(eq(SET), eq("k"), any())).thenReturn(1);
        when(syncSetRepo.findById(SET)).thenThrow(new RuntimeException("db hiccup"));

        worker.drainQueue();

        verify(engine).process(SET, "k", false);
        verify(requestRepo).deleteIfClaimed(SET, "k");
        verify(history, never()).finishIncremental(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void staleSweep_gatedOff_doesNothing() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(false);
        worker.releaseStaleClaims();
        verify(requestRepo, never()).releaseStaleClaims(any(OffsetDateTime.class));
    }
}
