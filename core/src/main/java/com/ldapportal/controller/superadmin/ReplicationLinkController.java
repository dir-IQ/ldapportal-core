// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.replication.ReplicationLinkRequest;
import com.ldapportal.dto.replication.ReplicationLinkResponse;
import com.ldapportal.service.ReplicationLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Replication-link CRUD. SUPERADMIN only because a misconfigured link
 * can silently corrupt a target directory.
 *
 * <pre>
 *   GET    /api/v1/superadmin/replication-links           — list (with health)
 *   POST   /api/v1/superadmin/replication-links           — create
 *   GET    /api/v1/superadmin/replication-links/{id}      — get one
 *   PUT    /api/v1/superadmin/replication-links/{id}      — update
 *   DELETE /api/v1/superadmin/replication-links/{id}      — delete
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/superadmin/replication-links")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiredArgsConstructor
public class ReplicationLinkController {

    private final ReplicationLinkService service;

    @GetMapping
    public List<ReplicationLinkResponse> list() {
        return service.listLinks();
    }

    @GetMapping("/{id}")
    public ReplicationLinkResponse get(@PathVariable UUID id) {
        return service.getLink(id);
    }

    @PostMapping
    public ResponseEntity<ReplicationLinkResponse> create(@AuthenticationPrincipal AuthPrincipal principal,
                                                           @Valid @RequestBody ReplicationLinkRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createLink(principal, req));
    }

    @PutMapping("/{id}")
    public ReplicationLinkResponse update(@AuthenticationPrincipal AuthPrincipal principal,
                                           @PathVariable UUID id,
                                           @Valid @RequestBody ReplicationLinkRequest req) {
        return service.updateLink(principal, id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthPrincipal principal,
                                        @PathVariable UUID id) {
        service.deleteLink(principal, id);
        return ResponseEntity.noContent().build();
    }
}
