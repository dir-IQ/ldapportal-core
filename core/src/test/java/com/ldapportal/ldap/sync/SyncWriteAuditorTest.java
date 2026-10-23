// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SyncWriteAuditorTest {

    private static final UUID DIR = UUID.randomUUID();
    private static final UUID SET = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();

    @Mock private AuditService auditService;
    @InjectMocks private SyncWriteAuditor auditor;

    private void audit(SyncPreviewAction action, SyncReconcileTrigger trigger) {
        auditor.audit(DIR, SET, RUN, trigger, new RecomputeOutcome(action, "uid=a,dc=dst", null));
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
    void outcomesThatWroteNothing_areNotAudited() {
        for (SyncPreviewAction a : new SyncPreviewAction[] {SyncPreviewAction.REVIEW, SyncPreviewAction.NO_CHANGE,
                SyncPreviewAction.FORGET, SyncPreviewAction.UNCHANGED, SyncPreviewAction.ERROR}) {
            audit(a, SyncReconcileTrigger.SCHEDULED);
        }
        auditor.audit(DIR, SET, RUN, SyncReconcileTrigger.SCHEDULED, null);
        auditor.audit(DIR, SET, RUN, SyncReconcileTrigger.SCHEDULED,
                new RecomputeOutcome(SyncPreviewAction.ADD, null, null));

        verifyNoInteractions(auditService);
    }
}
