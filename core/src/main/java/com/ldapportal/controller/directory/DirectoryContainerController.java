// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.service.LdapOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Helper endpoints for directory containers (OUs / organizations).
 *
 * <p>Backs the bulk-import "create missing parent DN" flow: the UI calls
 * {@code GET /exists?dn=…} before submitting an import, and if the parent
 * is absent prompts the user to create it via {@code POST /} before the
 * CSV upload. Decoupled from {@code BulkUserController} /
 * {@code BulkGroupController} since the same parent-validation flow
 * applies to both, and the create endpoint may grow other use cases.</p>
 *
 * <pre>
 *   GET  /api/v1/directories/{directoryId}/container/exists?dn=...
 *   POST /api/v1/directories/{directoryId}/container        body: {"dn": "..."}
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/container")
@RequiredArgsConstructor
public class DirectoryContainerController {

    private final LdapOperationService service;

    @GetMapping("/exists")
    @RequiresFeature(FeatureKey.DIRECTORY_BROWSE)
    public Map<String, Boolean> exists(
            @DirectoryId @PathVariable UUID directoryId,
            @RequestParam String dn,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return Map.of("exists", service.entryExists(directoryId, principal, dn));
    }

    @PostMapping
    @RequiresFeature(FeatureKey.BULK_IMPORT)
    public Map<String, String> create(
            @DirectoryId @PathVariable UUID directoryId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal AuthPrincipal principal) {
        String dn = body.get("dn");
        if (dn == null || dn.isBlank()) {
            throw new IllegalArgumentException("Missing 'dn' in request body");
        }
        service.createContainer(directoryId, principal, dn);
        return Map.of("dn", dn);
    }
}
