// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.repository.RecomputeRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
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

        verify(engine).process(SET, "k");
        verify(requestRepo).deleteIfClaimed(SET, "k");
        verify(requestRepo, never()).releaseClaim(any(), any());
    }

    @Test
    void drain_lostClaimRace_skipsProcessing() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(true);
        when(requestRepo.findBatchUnclaimed(any())).thenReturn(List.of(req("k")));
        when(requestRepo.claim(eq(SET), eq("k"), any())).thenReturn(0);

        worker.drainQueue();

        verify(engine, never()).process(any(), any());
        verify(requestRepo, never()).deleteIfClaimed(any(), any());
    }

    @Test
    void drain_unexpectedFault_releasesClaimForRetry() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(true);
        when(requestRepo.findBatchUnclaimed(any())).thenReturn(List.of(req("k")));
        when(requestRepo.claim(eq(SET), eq("k"), any())).thenReturn(1);
        doThrow(new RuntimeException("boom")).when(engine).process(SET, "k");

        worker.drainQueue();

        verify(requestRepo).releaseClaim(SET, "k");
        verify(requestRepo, never()).deleteIfClaimed(any(), any());
    }

    @Test
    void staleSweep_gatedOff_doesNothing() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(false);
        worker.releaseStaleClaims();
        verify(requestRepo, never()).releaseStaleClaims(any(OffsetDateTime.class));
    }
}
