// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.replication.FindingActionRequest;
import com.ldapportal.dto.replication.ReconciliationFindingResponse;
import com.ldapportal.dto.replication.ReconciliationRunResponse;
import com.ldapportal.entity.enums.ReconciliationFindingStatus;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;
import com.ldapportal.ldap.replication.reconcile.ReconciliationFindingService;
import com.ldapportal.ldap.replication.reconcile.ReconciliationService;
import com.ldapportal.service.ReplicationLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Reconciliation triggering, run history, and finding review for a
 * replication link. SUPERADMIN only — a run or an apply can enqueue
 * corrective writes (including deletes) against the target.
 *
 * <pre>
 *   POST /api/v1/superadmin/replication-links/{id}/reconcile             — run now
 *   GET  /api/v1/superadmin/replication-links/{id}/reconciliation-runs   — history
 *   GET  /api/v1/superadmin/replication-links/{id}/reconciliation-findings/open-count
 *   GET  /api/v1/superadmin/reconciliation-runs/{runId}/findings         — review list
 *   POST /api/v1/superadmin/reconciliation-runs/{runId}/findings/apply   — selective apply
 *   POST /api/v1/superadmin/reconciliation-runs/{runId}/findings/dismiss — selective dismiss
 * </pre>
 */
@RestController
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiredArgsConstructor
public class ReconciliationController {

    private final ReconciliationService        reconciliationService;
    private final ReconciliationFindingService findingService;
    private final ReplicationLinkService       linkService;

    @PostMapping("/api/v1/superadmin/replication-links/{id}/reconcile")
    public ResponseEntity<Map<String, String>> reconcileNow(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID id) {
        // 404 if the link doesn't exist (throws ResourceNotFoundException).
        linkService.getLink(id);
        return reconciliationService.trigger(id, ReconciliationRunTrigger.MANUAL, principal)
                .map(runId -> ResponseEntity.accepted().body(Map.of("runId", runId.toString())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("detail", "A reconciliation run is already in progress for this link")));
    }

    @GetMapping("/api/v1/superadmin/replication-links/{id}/reconciliation-runs")
    public Page<ReconciliationRunResponse> runs(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return reconciliationService.listRuns(id, PageRequest.of(page, size));
    }

    @GetMapping("/api/v1/superadmin/replication-links/{id}/reconciliation-findings/open-count")
    public Map<String, Long> openFindingCount(@PathVariable UUID id) {
        return Map.of("open", findingService.openCount(id));
    }

    @GetMapping("/api/v1/superadmin/reconciliation-runs/{runId}/findings")
    public Page<ReconciliationFindingResponse> findings(
            @PathVariable UUID runId,
            @RequestParam(required = false) ReconciliationFindingStatus status,
            @RequestParam(required = false) ReconciliationFindingType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return findingService.list(runId, status, type, PageRequest.of(page, size));
    }

    @PostMapping("/api/v1/superadmin/reconciliation-runs/{runId}/findings/apply")
    public Map<String, Integer> applyFindings(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID runId,
            @RequestBody FindingActionRequest req) {
        int applied = findingService.apply(principal, runId, req.findingIds(), req.applyAll(), req.type());
        return Map.of("applied", applied);
    }

    @PostMapping("/api/v1/superadmin/reconciliation-runs/{runId}/findings/dismiss")
    public Map<String, Integer> dismissFindings(
            @AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID runId,
            @RequestBody FindingActionRequest req) {
        int dismissed = findingService.dismiss(principal, runId, req.findingIds());
        return Map.of("dismissed", dismissed);
    }
}
