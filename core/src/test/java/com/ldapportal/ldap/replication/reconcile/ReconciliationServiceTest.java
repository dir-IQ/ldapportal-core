// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;
import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.ldap.replication.PendingReplicationEvent;
import com.ldapportal.ldap.replication.ReplicationEventPersister;
import com.ldapportal.ldap.replication.ReplicationLinkSnapshot;
import com.ldapportal.ldap.replication.ReplicationReadOps;
import com.ldapportal.ldap.replication.reconcile.ReconciliationTxOps.StartedRun;
import com.ldapportal.repository.ReconciliationRunRepository;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock private ReplicationReadOps         replicationReadOps;
    @Mock private ReconciliationReadOps      reconReadOps;
    @Mock private ReplicationEventPersister  persister;
    @Mock private ReplicationEventRepository eventRepo;
    @Mock private ReconciliationRunRepository runRepo;
    @Mock private ReconciliationTxOps        txOps;
    @Mock private AuditService               auditService;
    @InjectMocks private ReconciliationService service;

    private final UUID linkId = UUID.randomUUID();
    private final UUID runId  = UUID.randomUUID();
    private DirectoryConnection sourceDir;
    private DirectoryConnection targetDir;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "maxFindingsPerRun", 5000);
        sourceDir = directory("dc=x");
        targetDir = directory("dc=x");
        when(eventRepo.findUndeliveredTargetDns(linkId)).thenReturn(List.of());
    }

    private DirectoryConnection directory(String baseDn) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setBaseDn(baseDn);
        return dc;
    }

    private void stubSnapshot() {
        ReplicationLinkSnapshot snap = new ReplicationLinkSnapshot(
                linkId, "L", sourceDir, targetDir, null, null, true, false, List.of());
        when(replicationReadOps.snapshotById(linkId)).thenReturn(Optional.of(snap));
    }

    private ReconEntry e(String dn, String cn) {
        return new ReconEntry(dn, Map.of("cn", List.of(cn)));
    }

    @SuppressWarnings("unchecked")
    private List<PendingReplicationEvent> captureSaved() {
        ArgumentCaptor<List<PendingReplicationEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(persister).saveAll(cap.capture());
        return cap.getValue();
    }

    @Test
    void autoCorrect_enqueuesMissingAsReconciliationAdd() {
        stubSnapshot();
        when(reconReadOps.readSubtree(eq(sourceDir), anyString())).thenReturn(List.of(e("uid=b,dc=x", "Bob")));
        when(reconReadOps.readSubtree(eq(targetDir), anyString())).thenReturn(List.of());

        service.execute(new StartedRun(runId, ReconcileMode.AUTO_CORRECT, ReconcileDeleteAction.REVIEW),
                linkId, ReconciliationRunTrigger.SCHEDULED, null);

        List<PendingReplicationEvent> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).operation()).isEqualTo(ReplicationOperationType.ADD);
        assertThat(saved.get(0).enqueueSource()).isEqualTo(ReplicationEnqueueSource.RECONCILIATION);
        assertThat(saved.get(0).targetDn()).isEqualTo("uid=b,dc=x");
        verify(txOps).completeRun(eq(runId), any(), eq(1));
        verify(txOps).advanceSchedule(eq(linkId), any());
    }

    @Test
    void reviewMode_doesNotEnqueue() {
        stubSnapshot();
        when(reconReadOps.readSubtree(eq(sourceDir), anyString())).thenReturn(List.of(e("uid=b,dc=x", "Bob")));
        when(reconReadOps.readSubtree(eq(targetDir), anyString())).thenReturn(List.of());

        service.execute(new StartedRun(runId, ReconcileMode.REVIEW, ReconcileDeleteAction.REVIEW),
                linkId, ReconciliationRunTrigger.SCHEDULED, null);

        verify(persister, never()).saveAll(any());
        verify(txOps).completeRun(eq(runId), any(), eq(0));
    }

    @Test
    void autoDelete_enqueuesExtraAsDelete() {
        stubSnapshot();
        when(reconReadOps.readSubtree(eq(sourceDir), anyString())).thenReturn(List.of(e("uid=a,dc=x", "Ann")));
        when(reconReadOps.readSubtree(eq(targetDir), anyString())).thenReturn(List.of(
                e("uid=a,dc=x", "Ann"), e("uid=z,dc=x", "Zed")));

        // Review mode for missing/drift, but AUTO delete-action for extras.
        service.execute(new StartedRun(runId, ReconcileMode.REVIEW, ReconcileDeleteAction.AUTO),
                linkId, ReconciliationRunTrigger.SCHEDULED, null);

        List<PendingReplicationEvent> saved = captureSaved();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).operation()).isEqualTo(ReplicationOperationType.DELETE);
        assertThat(saved.get(0).targetDn()).isEqualTo("uid=z,dc=x");
    }

    @Test
    void exceedingSafetyCap_failsRunWithoutEnqueue() {
        ReflectionTestUtils.setField(service, "maxFindingsPerRun", 0);
        stubSnapshot();
        when(reconReadOps.readSubtree(eq(sourceDir), anyString())).thenReturn(List.of(e("uid=b,dc=x", "Bob")));
        when(reconReadOps.readSubtree(eq(targetDir), anyString())).thenReturn(List.of());

        service.execute(new StartedRun(runId, ReconcileMode.AUTO_CORRECT, ReconcileDeleteAction.REVIEW),
                linkId, ReconciliationRunTrigger.SCHEDULED, null);

        verify(persister, never()).saveAll(any());
        verify(txOps).failRun(eq(runId), anyString());
        verify(txOps, never()).completeRun(any(), any(), anyInt());
        verify(txOps).advanceSchedule(eq(linkId), any());
    }
}
