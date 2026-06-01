// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.replication.ReconciliationFindingResponse;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.ReconciliationFindingStatus;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.ldap.replication.reconcile.ReconciliationFindingTxOps.FindingSummary;
import com.ldapportal.repository.ReconciliationFindingRepository;
import com.ldapportal.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Query + operator-action surface for reconciliation findings (R-P2). The DB
 * work lives in {@link ReconciliationFindingTxOps}; this service adds the
 * read projection and the per-finding audit emission for apply / dismiss.
 */
@Service
@RequiredArgsConstructor
public class ReconciliationFindingService {

    private final ReconciliationFindingRepository findingRepo;
    private final ReconciliationFindingTxOps      txOps;
    private final AuditService                    auditService;

    @Transactional(readOnly = true)
    public Page<ReconciliationFindingResponse> list(UUID runId, ReconciliationFindingStatus status,
                                                    ReconciliationFindingType type, Pageable pageable) {
        return findingRepo.search(runId, status, type, pageable)
                .map(ReconciliationFindingResponse::from);
    }

    /** Open (PROPOSED) finding count for a link — backs the row badge. */
    @Transactional(readOnly = true)
    public long openCount(UUID linkId) {
        return findingRepo.countByLinkIdAndStatus(linkId, ReconciliationFindingStatus.PROPOSED);
    }

    /** Apply selected or all PROPOSED findings; returns the number applied. */
    public int apply(AuthPrincipal principal, UUID runId, List<UUID> findingIds,
                     boolean applyAll, ReconciliationFindingType typeFilter) {
        List<FindingSummary> applied = txOps.apply(runId, findingIds, applyAll, typeFilter, principal.id());
        for (FindingSummary s : applied) {
            auditService.recordSystemEvent(principal, AuditAction.RECONCILIATION_FINDING_APPLIED,
                    auditDetail(runId, s));
        }
        return applied.size();
    }

    /** Dismiss selected PROPOSED findings; returns the number dismissed. */
    public int dismiss(AuthPrincipal principal, UUID runId, List<UUID> findingIds) {
        List<FindingSummary> dismissed = txOps.dismiss(runId, findingIds, principal.id());
        for (FindingSummary s : dismissed) {
            auditService.recordSystemEvent(principal, AuditAction.RECONCILIATION_FINDING_DISMISSED,
                    auditDetail(runId, s));
        }
        return dismissed.size();
    }

    private static Map<String, Object> auditDetail(UUID runId, FindingSummary s) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("runId", runId.toString());
        detail.put("findingId", s.findingId().toString());
        detail.put("findingType", s.type().name());
        detail.put("targetDn", s.targetDn());
        if (s.eventId() != null) detail.put("eventId", s.eventId().toString());
        return detail;
    }
}
