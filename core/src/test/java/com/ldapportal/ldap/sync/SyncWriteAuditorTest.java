// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class SyncWriteAuditorTest {

    private static final UUID DIR = UUID.randomUUID();
    private static final UUID SET = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

    @Mock private AuditService auditService;
    @Mock private SyncLinkRepository linkRepo;
    @Mock private DirectoryConnectionRepository directoryRepo;
    @InjectMocks private SyncWriteAuditor auditor;

    private void audit(SyncPreviewAction action, SyncReconcileTrigger trigger) {
        auditor.audit(DIR, SET, RUN, trigger, null, new RecomputeOutcome(action, "uid=a,dc=dst", null));
    }

    @Test
    void eachTargetWrite_isAnEntryRecord_correlatedByRun() {
        audit(SyncPreviewAction.ADD, SyncReconcileTrigger.SCHEDULED);
        audit(SyncPreviewAction.MODIFY, SyncReconcileTrigger.MANUAL);
        audit(SyncPreviewAction.MOVE, SyncReconcileTrigger.INCREMENTAL);
        audit(SyncPreviewAction.DELETE, SyncReconcileTrigger.INCREMENTAL);

        Map<String, Object> reconcile = Map.of("source", "directory-sync", "operation", "reconcile",
                "trigger", "SCHEDULED", "syncSetId", SET.toString(), "runId", RUN.toString());
        verify(auditService).recordDirectoryEventNoActor(DIR, AuditAction.ENTRY_CREATE, "uid=a,dc=dst",
                reconcile, RUN);
        verify(auditService).recordDirectoryEventNoActor(eq(DIR), eq(AuditAction.ENTRY_UPDATE), eq("uid=a,dc=dst"),
                any(), eq(RUN));
        verify(auditService).recordDirectoryEventNoActor(eq(DIR), eq(AuditAction.ENTRY_MOVE), eq("uid=a,dc=dst"),
                eq(Map.of("source", "directory-sync", "operation", "incremental", "trigger", "INCREMENTAL",
                        "syncSetId", SET.toString(), "runId", RUN.toString())), eq(RUN));
        verify(auditService).recordDirectoryEventNoActor(eq(DIR), eq(AuditAction.ENTRY_DELETE), eq("uid=a,dc=dst"),
                any(), eq(RUN));
    }

    @Test
    void anUpdate_namesTheAttributesItChanged() {
        auditor.audit(DIR, SET, RUN, SyncReconcileTrigger.INCREMENTAL, null,
                new RecomputeOutcome(SyncPreviewAction.MODIFY, "uid=a,dc=dst", null, List.of("mail", "member")));

        verify(auditService).recordDirectoryEventNoActor(DIR, AuditAction.ENTRY_UPDATE, "uid=a,dc=dst",
                Map.of("source", "directory-sync", "operation", "incremental", "trigger", "INCREMENTAL",
                        "syncSetId", SET.toString(), "runId", RUN.toString(),
                        "attributes", List.of("mail", "member")), RUN);
    }

    @Test
    void outcomesThatWroteNothing_areNotAudited() {
        for (SyncPreviewAction a : new SyncPreviewAction[] {SyncPreviewAction.REVIEW, SyncPreviewAction.NO_CHANGE,
                SyncPreviewAction.FORGET, SyncPreviewAction.UNCHANGED, SyncPreviewAction.ERROR}) {
            audit(a, SyncReconcileTrigger.SCHEDULED);
        }
        auditor.audit(DIR, SET, RUN, SyncReconcileTrigger.SCHEDULED, null, null);
        auditor.audit(DIR, SET, RUN, SyncReconcileTrigger.SCHEDULED, null,
                new RecomputeOutcome(SyncPreviewAction.ADD, null, null));

        verifyNoInteractions(auditService);
    }

    @Test
    void eachRecord_namesTheSyncSetAndBothDirectories() {
        auditor.audit(DIR, SET, RUN, SyncReconcileTrigger.MANUAL,
                new SyncAuditLabels("people", "HR LDAP", "Corp AD"),
                new RecomputeOutcome(SyncPreviewAction.ADD, "uid=a,dc=dst", null));

        verify(auditService).recordDirectoryEventNoActor(DIR, AuditAction.ENTRY_CREATE, "uid=a,dc=dst",
                Map.of("source", "directory-sync", "operation", "reconcile", "trigger", "MANUAL",
                        "syncSetName", "people", "sourceDirectory", "HR LDAP", "targetDirectory", "Corp AD",
                        "syncSetId", SET.toString(), "runId", RUN.toString()), RUN);
    }

    @Test
    void labelsFor_resolvesTheSetNameAndTheLinksDirectoryNames() {
        UUID linkId = UUID.randomUUID();
        UUID sourceDir = UUID.randomUUID();
        SyncSet set = new SyncSet();
        set.setName("people");
        set.setLinkId(linkId);
        SyncLink link = new SyncLink();
        link.setSourceDirId(sourceDir);
        link.setTargetDirId(DIR);
        when(linkRepo.findById(linkId)).thenReturn(Optional.of(link));
        when(directoryRepo.findById(sourceDir)).thenReturn(Optional.of(directory("HR LDAP")));
        when(directoryRepo.findById(DIR)).thenReturn(Optional.empty());

        // A directory that's gone is left out rather than failing the run.
        assertThat(auditor.labelsFor(set)).isEqualTo(new SyncAuditLabels("people", "HR LDAP", null));
    }

    private static DirectoryConnection directory(String name) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDisplayName(name);
        return dc;
    }
}
