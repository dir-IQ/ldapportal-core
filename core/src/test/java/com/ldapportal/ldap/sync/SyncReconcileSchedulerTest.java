// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.dto.sync.SyncVerifyResult;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.repository.SyncSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The scheduled anti-entropy sweep both reconciles a due set and refreshes its
 * cached content-verify snapshot (the drift the dashboard surfaces). A partial
 * verify must not overwrite a good snapshot, and the whole sweep is gated on the
 * {@code DIRECTORY_SYNC} entitlement.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncReconcileSchedulerTest {

    @Mock private SyncSetRepository syncSetRepo;
    @Mock private MembershipReconciler reconciler;
    @Mock private SyncContentVerifier verifier;
    @Mock private EntitlementService entitlementService;

    private SyncReconcileScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SyncReconcileScheduler(syncSetRepo, reconciler, verifier, entitlementService, 3600L);
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(true);
    }

    @Test
    void reconcilesDueSet_andCachesCompleteVerify() {
        UUID id = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(id); // reconcileLastRunAt null => due
        when(syncSetRepo.findAllByEnabledTrue()).thenReturn(List.of(set));
        when(verifier.verify(id)).thenReturn(result(2, 1, 0, true, true));

        scheduler.reconcileDueSets();

        verify(reconciler).reconcile(id, SyncReconcileTrigger.SCHEDULED, null);
        verify(syncSetRepo).recordVerifyResult(eq(id), any(), eq(2), eq(1), eq(0));
    }

    @Test
    void setReconcilingElsewhere_isSkippedWithoutAVerify_andTheSweepContinues() {
        UUID busy = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        SyncSet a = new SyncSet();
        a.setId(busy);
        SyncSet b = new SyncSet();
        b.setId(free);
        when(syncSetRepo.findAllByEnabledTrue()).thenReturn(List.of(a, b));
        when(reconciler.reconcile(busy, SyncReconcileTrigger.SCHEDULED, null))
                .thenThrow(new ConflictException("A reconcile of this sync set is already running."));
        when(verifier.verify(free)).thenReturn(result(0, 0, 0, true, true));

        scheduler.reconcileDueSets();

        verify(verifier, never()).verify(busy);
        verify(reconciler).reconcile(free, SyncReconcileTrigger.SCHEDULED, null);
    }

    @Test
    void skipsSnapshot_onPartialVerify() {
        UUID id = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(id);
        when(syncSetRepo.findAllByEnabledTrue()).thenReturn(List.of(set));
        when(verifier.verify(id)).thenReturn(result(0, 0, 0, false, true)); // source enum failed

        scheduler.reconcileDueSets();

        verify(reconciler).reconcile(id, SyncReconcileTrigger.SCHEDULED, null);
        verify(syncSetRepo, never()).recordVerifyResult(any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void pendingReconcileRequest_makesARecentlyReconciledSetDue() {
        UUID id = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(id);
        set.setReconcileLastRunAt(java.time.OffsetDateTime.now()); // cadence not elapsed
        set.setReconcileRequestSeq(3);
        set.setReconcileAttemptedSeq(2);
        set.setReconcileHandledSeq(2);                             // one request, not yet attempted
        when(syncSetRepo.findAllByEnabledTrue()).thenReturn(List.of(set));
        when(verifier.verify(id)).thenReturn(result(0, 0, 0, true, true));

        scheduler.reconcileDueSets();

        verify(reconciler).reconcile(id, SyncReconcileTrigger.SCHEDULED, null);
    }

    @Test
    void handledReconcileRequest_recentRun_isNotDue() {
        UUID id = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(id);
        set.setReconcileLastRunAt(java.time.OffsetDateTime.now());
        set.setReconcileRequestSeq(3);
        set.setReconcileAttemptedSeq(3);
        set.setReconcileHandledSeq(3);
        when(syncSetRepo.findAllByEnabledTrue()).thenReturn(List.of(set));

        scheduler.reconcileDueSets();

        verify(reconciler, never()).reconcile(any(), any(), any());
    }

    @Test
    void unhandledRequest_isRetriedOnlyAfterTheRetrySpacing() {
        // Attempted by a reconcile whose enumeration didn't complete: not
        // handled. A retry waits REQUEST_RETRY_SPACING after that run, so a
        // persistently failing source isn't re-enumerated every tick.
        UUID id = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(id);
        set.setReconcileRequestSeq(3);
        set.setReconcileAttemptedSeq(3);
        set.setReconcileHandledSeq(2);
        set.setReconcileLastRunAt(java.time.OffsetDateTime.now().minusMinutes(1));
        when(syncSetRepo.findAllByEnabledTrue()).thenReturn(List.of(set));
        when(verifier.verify(id)).thenReturn(result(0, 0, 0, true, true));

        scheduler.reconcileDueSets();
        verify(reconciler, never()).reconcile(any(), any(), any());

        set.setReconcileLastRunAt(java.time.OffsetDateTime.now()
                .minus(SyncReconcileScheduler.REQUEST_RETRY_SPACING).minusSeconds(1));
        scheduler.reconcileDueSets();
        verify(reconciler).reconcile(id, SyncReconcileTrigger.SCHEDULED, null);
    }

    @Test
    void doesNothing_whenDirectorySyncNotEntitled() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(false);

        scheduler.reconcileDueSets();

        verifyNoInteractions(syncSetRepo, reconciler, verifier);
    }

    private static SyncVerifyResult result(int missing, int orphan, int mismatch,
                                           boolean sourceComplete, boolean targetComplete) {
        return new SyncVerifyResult(0, 0, 0, missing, orphan, mismatch,
                List.of(), List.of(), List.of(), sourceComplete, targetComplete, null);
    }
}
