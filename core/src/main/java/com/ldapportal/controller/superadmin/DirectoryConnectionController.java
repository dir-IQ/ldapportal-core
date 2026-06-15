// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.dto.directory.DirectoryConnectionRequest;
import com.ldapportal.dto.directory.DirectoryConnectionResponse;
import com.ldapportal.dto.directory.TestConnectionRequest;
import com.ldapportal.dto.directory.TestConnectionResult;
import com.ldapportal.service.DirectoryConnectionService;
import com.ldapportal.web.ETagSupport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public ResponseEntity<DirectoryConnectionResponse> get(@PathVariable UUID id) {
        return withETag(service.getDirectory(id), HttpStatus.OK);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DirectoryConnectionResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody DirectoryConnectionRequest req,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        DirectoryConnectionResponse resp =
                service.updateDirectory(id, req, ETagSupport.parseIfMatch(ifMatch));
        return withETag(resp, HttpStatus.OK);
    }

    /**
     * Idempotent create-or-update keyed by the stable IaC slug. Re-applying
     * the same declaration converges to identical state; returns 201 on the
     * first apply (resource created) and 200 on subsequent applies (updated
     * in place). The slug is immutable, so this never renames an existing
     * directory's key.
     *
     * <p>An optional {@code If-Match} header makes the update-path apply
     * conditional on the directory still being at the version the caller last
     * saw (412 on mismatch); it is ignored when the apply creates the
     * directory (no prior version).</p>
     */
    @PutMapping("/by-slug/{slug}")
    public ResponseEntity<DirectoryConnectionResponse> upsertBySlug(
            @PathVariable String slug,
            @Valid @RequestBody DirectoryConnectionRequest req,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        DirectoryConnectionService.UpsertOutcome outcome =
                service.upsertBySlug(slug, req, ETagSupport.parseIfMatch(ifMatch));
        return withETag(outcome.response(), outcome.created() ? HttpStatus.CREATED : HttpStatus.OK);
    }

    /** Attach the resource's version as a strong ETag for optimistic concurrency. */
    private static ResponseEntity<DirectoryConnectionResponse> withETag(
            DirectoryConnectionResponse resp, HttpStatus status) {
        return ResponseEntity.status(status)
                .eTag(ETagSupport.format(resp.version()))
                .body(resp);
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
