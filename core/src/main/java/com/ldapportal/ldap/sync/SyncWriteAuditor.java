// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Audits each write the sync engine made to a target directory during a run:
 * an add, modify, move or delete becomes an {@code entry.*} record on the
 * target directory, with no actor (the engine wrote it, whoever started the
 * run) and the run's id as its correlation id, so a run's writes can be pulled
 * up together from the audit log. An update also names the attributes it
 * changed ({@code detail.attributes}; names only, never values). Outcomes that wrote nothing (no change,
 * held for review, forgotten, failed) aren't audited; the run history counts
 * them. Operator-approved review deletes are audited where they happen, with
 * the operator as actor.
 */
@Component
@RequiredArgsConstructor
public class SyncWriteAuditor {

    private final AuditService auditService;

    /** Record one outcome of a run, if it was a target write. */
    public void audit(UUID targetDirectoryId, UUID syncSetId, UUID runId, SyncReconcileTrigger trigger,
                      RecomputeOutcome outcome) {
        if (outcome == null || outcome.targetDn() == null || targetDirectoryId == null) {
            return;
        }
        AuditAction action = actionFor(outcome.action());
        if (action == null) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source", "directory-sync");
        detail.put("operation", trigger == SyncReconcileTrigger.INCREMENTAL ? "incremental" : "reconcile");
        detail.put("trigger", trigger.name());
        detail.put("syncSetId", syncSetId.toString());
        detail.put("runId", runId.toString());
        if (!outcome.attributes().isEmpty()) {
            // Which attributes an update (or the update after a move) changed: names only, never values.
            detail.put("attributes", outcome.attributes());
        }
        auditService.recordDirectoryEventNoActor(targetDirectoryId, action, outcome.targetDn(), detail, runId);
    }

    static AuditAction actionFor(SyncPreviewAction outcome) {
        return switch (outcome) {
            case ADD -> AuditAction.ENTRY_CREATE;
            case MODIFY -> AuditAction.ENTRY_UPDATE;
            case MOVE -> AuditAction.ENTRY_MOVE;
            case DELETE -> AuditAction.ENTRY_DELETE;
            default -> null;
        };
    }
}
