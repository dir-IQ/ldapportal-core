// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.dto.sync.SyncVerifyResult;
import com.ldapportal.entity.SyncSet;
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

        verify(reconciler).reconcile(id);
        verify(syncSetRepo).recordVerifyResult(eq(id), any(), eq(2), eq(1), eq(0));
    }

    @Test
    void skipsSnapshot_onPartialVerify() {
        UUID id = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setId(id);
        when(syncSetRepo.findAllByEnabledTrue()).thenReturn(List.of(set));
        when(verifier.verify(id)).thenReturn(result(0, 0, 0, false, true)); // source enum failed

        scheduler.reconcileDueSets();

        verify(reconciler).reconcile(id);
        verify(syncSetRepo, never()).recordVerifyResult(any(), any(), anyInt(), anyInt(), anyInt());
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
