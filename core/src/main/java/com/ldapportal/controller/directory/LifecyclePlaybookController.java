// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.dto.playbook.*;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.service.LifecyclePlaybookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class LifecyclePlaybookController {

    private final LifecyclePlaybookService service;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/directories/{directoryId}/playbooks")
    @RequiresFeature(FeatureKey.PLAYBOOK_MANAGE)
    public List<PlaybookResponse> list(@DirectoryId @PathVariable UUID directoryId) {
        return service.list(directoryId);
    }

    @GetMapping("/api/v1/directories/{directoryId}/playbooks/enabled")
    @RequiresFeature(FeatureKey.PLAYBOOK_MANAGE)
    public List<PlaybookResponse> listEnabled(@DirectoryId @PathVariable UUID directoryId) {
        return service.listEnabled(directoryId);
    }

    @PostMapping("/api/v1/directories/{directoryId}/playbooks")
    @RequiresFeature(FeatureKey.PLAYBOOK_MANAGE)
    public ResponseEntity<PlaybookResponse> create(@DirectoryId @PathVariable UUID directoryId,
                                                    @Valid @RequestBody CreatePlaybookRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(directoryId, req));
    }

    @GetMapping("/api/v1/directories/{directoryId}/playbooks/{playbookId}")
    @RequiresFeature(FeatureKey.PLAYBOOK_MANAGE)
    public PlaybookResponse get(@DirectoryId @PathVariable UUID directoryId,
                                 @PathVariable UUID playbookId) {
        return service.get(directoryId, playbookId);
    }

    @PutMapping("/api/v1/directories/{directoryId}/playbooks/{playbookId}")
    @RequiresFeature(FeatureKey.PLAYBOOK_MANAGE)
    public PlaybookResponse update(@DirectoryId @PathVariable UUID directoryId,
                                    @PathVariable UUID playbookId,
                                    @Valid @RequestBody UpdatePlaybookRequest req) {
        return service.update(directoryId, playbookId, req);
    }

    @DeleteMapping("/api/v1/directories/{directoryId}/playbooks/{playbookId}")
    @RequiresFeature(FeatureKey.PLAYBOOK_MANAGE)
    public ResponseEntity<Void> delete(@DirectoryId @PathVariable UUID directoryId,
                                        @PathVariable UUID playbookId) {
        service.delete(directoryId, playbookId);
        return ResponseEntity.noContent().build();
    }

    // ── Preview & Execute ─────────────────────────────────────────────────────

    @PostMapping("/api/v1/directories/{directoryId}/playbooks/{playbookId}/preview")
    @RequiresFeature(FeatureKey.PLAYBOOK_MANAGE)
    public PlaybookPreviewResponse preview(@DirectoryId @PathVariable UUID directoryId,
                                            @PathVariable UUID playbookId,
                                            @RequestParam String dn,
                                            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.preview(directoryId, playbookId, dn, principal);
    }

    @PostMapping("/api/v1/directories/{directoryId}/playbooks/{playbookId}/execute")
    @RequiresFeature(FeatureKey.PLAYBOOK_EXECUTE)
    public List<PlaybookExecutionResponse> execute(@DirectoryId @PathVariable UUID directoryId,
                                                    @PathVariable UUID playbookId,
                                                    @Valid @RequestBody ExecutePlaybookRequest req,
                                                    @AuthenticationPrincipal AuthPrincipal principal) {
        return req.targetDns().stream()
                .map(dn -> service.execute(directoryId, playbookId, dn, principal))
                .toList();
    }

    // ── Rollback ──────────────────────────────────────────────────────────────

    @PostMapping("/api/v1/directories/{directoryId}/playbooks/executions/{executionId}/rollback")
    @RequiresFeature(FeatureKey.PLAYBOOK_EXECUTE)
    public PlaybookExecutionResponse rollback(@DirectoryId @PathVariable UUID directoryId,
                                               @PathVariable UUID executionId,
                                               @AuthenticationPrincipal AuthPrincipal principal) {
        return service.rollback(executionId, principal);
    }

    // ── History ───────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/directories/{directoryId}/playbooks/{playbookId}/executions")
    @RequiresFeature(FeatureKey.PLAYBOOK_MANAGE)
    public List<PlaybookExecutionResponse> listExecutions(@DirectoryId @PathVariable UUID directoryId,
                                                           @PathVariable UUID playbookId) {
        return service.listExecutions(directoryId, playbookId);
    }
}
