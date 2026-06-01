// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.ReconciliationFinding;
import com.ldapportal.entity.ReconciliationRun;
import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReconciliationFindingStatus;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.ldap.replication.reconcile.ReconciliationFindingTxOps.FindingSummary;
import com.ldapportal.repository.ReconciliationFindingRepository;
import com.ldapportal.repository.ReplicationEventRepository;
import jakarta.persistence.EntityManager;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReconciliationFindingTxOpsTest {

    @Mock private ReconciliationFindingRepository findingRepo;
    @Mock private ReplicationEventRepository      eventRepo;
    @Mock private EntityManager                   em;
    @InjectMocks private ReconciliationFindingTxOps txOps;

    private final UUID runId  = UUID.randomUUID();
    private final UUID linkId = UUID.randomUUID();
    private final UUID actor  = UUID.randomUUID();

    @BeforeEach
    void setup() {
        // @InjectMocks uses the constructor for the repos and skips the
        // @PersistenceContext field, so wire the EntityManager mock manually.
        ReflectionTestUtils.setField(txOps, "em", em);
        lenient().when(em.getReference(eq(ReconciliationRun.class), any())).thenReturn(new ReconciliationRun());
        lenient().when(em.getReference(eq(ReplicationLink.class), any())).thenReturn(new ReplicationLink());
        // saved events get an id so finding.eventId can be asserted.
        lenient().when(eventRepo.save(any())).thenAnswer(inv -> {
            ReplicationEvent e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
    }

    private FindingCandidate missing(String dn) {
        return new FindingCandidate(ReconciliationFindingType.MISSING_IN_TARGET,
                ReplicationOperationType.ADD, dn, dn, Map.of("attributes", Map.of("cn", List.of("X"))));
    }

    private FindingCandidate extra(String dn) {
        return new FindingCandidate(ReconciliationFindingType.EXTRA_IN_TARGET,
                ReplicationOperationType.DELETE, null, dn, Map.of("currentTarget", Map.of()));
    }

    private ReconciliationFinding savedFinding() {
        ArgumentCaptor<ReconciliationFinding> cap = ArgumentCaptor.forClass(ReconciliationFinding.class);
        verify(findingRepo).save(cap.capture());
        return cap.getValue();
    }

    @Test
    void persist_autoCorrectMissing_enqueuesAndMarksAutoApplied() {
        int applied = txOps.persistFindings(runId, linkId, List.of(missing("uid=b,dc=x")),
                ReconcileMode.AUTO_CORRECT, ReconcileDeleteAction.REVIEW);

        assertThat(applied).isEqualTo(1);
        verify(eventRepo).save(any());
        ReconciliationFinding f = savedFinding();
        assertThat(f.getStatus()).isEqualTo(ReconciliationFindingStatus.AUTO_APPLIED);
        assertThat(f.getEventId()).isNotNull();
    }

    @Test
    void persist_reviewMode_marksProposedWithoutEvent() {
        int applied = txOps.persistFindings(runId, linkId, List.of(missing("uid=b,dc=x")),
                ReconcileMode.REVIEW, ReconcileDeleteAction.REVIEW);

        assertThat(applied).isZero();
        verify(eventRepo, never()).save(any());
        assertThat(savedFinding().getStatus()).isEqualTo(ReconciliationFindingStatus.PROPOSED);
    }

    @Test
    void persist_supersedesPriorOpenFindingsForLink_beforeInserting() {
        txOps.persistFindings(runId, linkId, List.of(missing("uid=b,dc=x")),
                ReconcileMode.REVIEW, ReconcileDeleteAction.REVIEW);

        // The fresh run retires earlier runs' still-open proposals for this
        // link so they don't pile up / double-count for the same DN.
        verify(findingRepo).supersedeOpenForLink(eq(linkId), any());
    }

    @Test
    void persist_extraHeldForReview_evenWhenAutoCorrect() {
        // AUTO_CORRECT applies missing/drift, but delete-action REVIEW holds extras.
        int applied = txOps.persistFindings(runId, linkId, List.of(extra("uid=z,dc=x")),
                ReconcileMode.AUTO_CORRECT, ReconcileDeleteAction.REVIEW);

        assertThat(applied).isZero();
        verify(eventRepo, never()).save(any());
        assertThat(savedFinding().getStatus()).isEqualTo(ReconciliationFindingStatus.PROPOSED);
    }

    @Test
    void persist_extraAutoDelete_enqueuesDelete() {
        int applied = txOps.persistFindings(runId, linkId, List.of(extra("uid=z,dc=x")),
                ReconcileMode.REVIEW, ReconcileDeleteAction.AUTO);

        assertThat(applied).isEqualTo(1);
        verify(eventRepo).save(any());
        assertThat(savedFinding().getStatus()).isEqualTo(ReconciliationFindingStatus.AUTO_APPLIED);
    }

    @Test
    void apply_selectedProposed_enqueuesAndMarksApplied() {
        ReconciliationFinding f = proposed("uid=b,dc=x");
        when(findingRepo.findByRunIdAndIdIn(eq(runId), any())).thenReturn(List.of(f));

        List<FindingSummary> applied = txOps.apply(runId, List.of(f.getId()), false, null, actor);

        assertThat(applied).hasSize(1);
        assertThat(f.getStatus()).isEqualTo(ReconciliationFindingStatus.APPLIED);
        assertThat(f.getEventId()).isNotNull();
        assertThat(f.getResolvedBy()).isEqualTo(actor);
        verify(eventRepo).save(any());
    }

    @Test
    void apply_skipsAlreadyResolved() {
        ReconciliationFinding f = proposed("uid=b,dc=x");
        f.setStatus(ReconciliationFindingStatus.DISMISSED);   // not PROPOSED
        when(findingRepo.findByRunIdAndIdIn(eq(runId), any())).thenReturn(List.of(f));

        List<FindingSummary> applied = txOps.apply(runId, List.of(f.getId()), false, null, actor);

        assertThat(applied).isEmpty();
        verify(eventRepo, never()).save(any());
    }

    @Test
    void dismiss_marksDismissed() {
        ReconciliationFinding f = proposed("uid=b,dc=x");
        when(findingRepo.findByRunIdAndIdIn(eq(runId), any())).thenReturn(List.of(f));

        List<FindingSummary> dismissed = txOps.dismiss(runId, List.of(f.getId()), actor);

        assertThat(dismissed).hasSize(1);
        assertThat(f.getStatus()).isEqualTo(ReconciliationFindingStatus.DISMISSED);
        assertThat(f.getResolvedBy()).isEqualTo(actor);
        verify(eventRepo, never()).save(any());
    }

    private ReconciliationFinding proposed(String dn) {
        ReconciliationFinding f = new ReconciliationFinding();
        f.setId(UUID.randomUUID());
        f.setLink(new ReplicationLink());
        f.setFindingType(ReconciliationFindingType.MISSING_IN_TARGET);
        f.setSuggestedOp(ReplicationOperationType.ADD);
        f.setSourceDn(dn);
        f.setTargetDn(dn);
        f.setDetail(Map.of("attributes", Map.of("cn", List.of("X"))));
        f.setStatus(ReconciliationFindingStatus.PROPOSED);
        return f;
    }
}
