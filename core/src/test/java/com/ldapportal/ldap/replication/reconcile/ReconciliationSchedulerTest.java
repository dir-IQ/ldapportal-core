// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;
import com.ldapportal.repository.ReplicationLinkRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationSchedulerTest {

    @Mock private ReplicationLinkRepository linkRepo;
    @Mock private ReconciliationService     service;
    @Mock private ReconciliationTxOps       txOps;
    @Mock private EntitlementService        entitlementService;

    private ReconciliationScheduler scheduler() {
        return new ReconciliationScheduler(linkRepo, service, txOps, entitlementService);
    }

    @Test
    void sweep_skipsEntirely_whenDirectorySyncNotEntitled() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(false);

        scheduler().sweep();

        // No corrective writes may flow on an unlicensed/community edition:
        // the autonomous path must not even look for due links.
        verify(linkRepo, never()).findReconcileDueIds(any());
        verify(service, never()).trigger(any(), any(), any());
    }

    @Test
    void sweep_triggersDueLinks_whenEntitled() {
        when(entitlementService.has(Entitlement.DIRECTORY_SYNC)).thenReturn(true);
        UUID due = UUID.randomUUID();
        when(linkRepo.findReconcileDueIds(any())).thenReturn(List.of(due));

        scheduler().sweep();

        verify(service).trigger(eq(due), eq(ReconciliationRunTrigger.SCHEDULED), eq(null));
    }
}
