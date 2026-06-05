// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.dto.directory.DirectoryConnectionRequest;
import com.ldapportal.dto.directory.DirectoryConnectionResponse;
import com.ldapportal.dto.directory.TestConnectionRequest;
import com.ldapportal.dto.directory.TestConnectionResult;
import com.ldapportal.service.DirectoryConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Directory connection management.
 *
 * <pre>
 *   GET    /api/v1/superadmin/directories               — list
 *   POST   /api/v1/superadmin/directories               — create
 *   GET    /api/v1/superadmin/directories/{id}          — get
 *   PUT    /api/v1/superadmin/directories/{id}          — update
 *   PUT    /api/v1/superadmin/directories/by-slug/{slug} — idempotent upsert (IaC)
 *   DELETE /api/v1/superadmin/directories/{id}          — delete
 *   POST   /api/v1/superadmin/directories/{id}/evict-pool — evict LDAP pool
 *   GET    /api/v1/superadmin/directories/{id}/status     — live reachability probe
 *   POST   /api/v1/superadmin/directories/test          — test (not persisted)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/superadmin/directories")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiredArgsConstructor
public class DirectoryConnectionController {

    private final DirectoryConnectionService service;

    @GetMapping
    public List<DirectoryConnectionResponse> list() {
        return service.listDirectories();
    }

    @PostMapping
    public ResponseEntity<DirectoryConnectionResponse> create(
            @Valid @RequestBody DirectoryConnectionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDirectory(req));
    }

    @GetMapping("/{id}")
    public DirectoryConnectionResponse get(@PathVariable UUID id) {
        return service.getDirectory(id);
    }

    @PutMapping("/{id}")
    public DirectoryConnectionResponse update(@PathVariable UUID id,
                                              @Valid @RequestBody DirectoryConnectionRequest req) {
        return service.updateDirectory(id, req);
    }

    /**
     * Idempotent create-or-update keyed by the stable IaC slug. Re-applying
     * the same declaration converges to identical state; returns 201 on the
     * first apply (resource created) and 200 on subsequent applies (updated
     * in place). The slug is immutable, so this never renames an existing
     * directory's key.
     */
    @PutMapping("/by-slug/{slug}")
    public ResponseEntity<DirectoryConnectionResponse> upsertBySlug(
            @PathVariable String slug,
            @Valid @RequestBody DirectoryConnectionRequest req) {
        DirectoryConnectionService.UpsertOutcome outcome = service.upsertBySlug(slug, req);
        return ResponseEntity
                .status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.response());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.deleteDirectory(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/evict-pool")
    public ResponseEntity<Void> evictPool(@PathVariable UUID id) {
        service.evictPool(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/status")
    public TestConnectionResult status(@PathVariable UUID id) {
        return service.checkConnection(id);
    }

    @PostMapping("/test")
    public TestConnectionResult test(@Valid @RequestBody TestConnectionRequest req) {
        return service.testConnection(req);
    }
}
