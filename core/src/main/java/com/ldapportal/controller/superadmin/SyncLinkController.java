// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.core.entitlement.Entitled;
import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.dto.sync.SyncLinkRequest;
import com.ldapportal.dto.sync.SyncLinkResponse;
import com.ldapportal.service.SyncConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * CRUD for sync links (source→target sync pairings). Gated on the
 * {@code DIRECTORY_SYNC} entitlement and superadmin.
 */
@RestController
@RequestMapping("/api/v1/superadmin/sync/links")
@Entitled(Entitlement.DIRECTORY_SYNC)
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiredArgsConstructor
public class SyncLinkController {

    private final SyncConfigService service;

    @GetMapping
    public List<SyncLinkResponse> list() {
        return service.listLinks();
    }

    @PostMapping
    public ResponseEntity<SyncLinkResponse> create(@Valid @RequestBody SyncLinkRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createLink(req));
    }

    @GetMapping("/{id}")
    public SyncLinkResponse get(@PathVariable UUID id) {
        return service.getLink(id);
    }

    @PutMapping("/{id}")
    public SyncLinkResponse update(@PathVariable UUID id, @Valid @RequestBody SyncLinkRequest req) {
        return service.updateLink(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.deleteLink(id);
        return ResponseEntity.noContent().build();
    }
}
