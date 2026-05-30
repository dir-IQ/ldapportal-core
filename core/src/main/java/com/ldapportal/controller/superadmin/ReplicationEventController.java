// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.replication.ReplicationEventResponse;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.service.ReplicationEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Per-link event log + operator actions. SUPERADMIN only — operator
 * actions can re-queue events that would otherwise stay dead-lettered
 * for review, which has the same blast radius as configuring a link
 * incorrectly.
 *
 * <pre>
 *   GET    /api/v1/superadmin/replication-links/{linkId}/events
 *                                                            — paginated event log,
 *                                                              optional ?status= filter
 *   POST   /api/v1/superadmin/replication-events/{id}/retry  — re-queue (FAILED/DEAD_LETTERED → PENDING)
 *   POST   /api/v1/superadmin/replication-events/{id}/skip   — discard without applying
 *   POST   /api/v1/superadmin/replication-events/{id}/acknowledge
 *                                                            — mark DEAD_LETTERED as seen
 * </pre>
 *
 * <p>The events list endpoint lives under the link path (not under
 * {@code /replication-events} on its own) because the operator
 * always cares about events <em>in the context of a link</em> — the
 * dedicated UI page shows them via the link detail view.
 */
@RestController
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiredArgsConstructor
public class ReplicationEventController {

    private final ReplicationEventService service;

    @GetMapping("/api/v1/superadmin/replication-links/{linkId}/events")
    public Page<ReplicationEventResponse> listForLink(
            @PathVariable UUID linkId,
            @RequestParam(required = false) ReplicationEventStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listForLink(linkId, status, page, size);
    }

    @PostMapping("/api/v1/superadmin/replication-events/{id}/retry")
    public ResponseEntity<Void> retry(@AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable UUID id) {
        service.retry(principal, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/superadmin/replication-events/{id}/skip")
    public ResponseEntity<Void> skip(@AuthenticationPrincipal AuthPrincipal principal,
                                      @PathVariable UUID id) {
        service.skip(principal, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/superadmin/replication-events/{id}/acknowledge")
    public ResponseEntity<Void> acknowledge(@AuthenticationPrincipal AuthPrincipal principal,
                                             @PathVariable UUID id) {
        service.acknowledge(principal, id);
        return ResponseEntity.noContent().build();
    }
}
