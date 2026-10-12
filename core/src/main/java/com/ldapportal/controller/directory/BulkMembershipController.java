// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.auth.ApiRateLimiter;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.PermissionService;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.dto.csv.BulkMembershipPreviewResult;
import com.ldapportal.dto.csv.BulkMembershipResult;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.service.BulkMembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Bulk group membership from a CSV upload — each row names a user (uid or
 * DN) and the groups (cn or DN) to add them to or remove them from. See
 * {@link BulkMembershipService} for the file format.
 *
 * <pre>
 *   POST /api/v1/directories/{directoryId}/memberships/bulk/preview — dry run, no writes
 *   POST /api/v1/directories/{directoryId}/memberships/bulk         — apply
 * </pre>
 *
 * <p>Both need {@code bulk.import} (the annotation) and
 * {@code group.manage_members} (checked in the method — the annotation
 * takes a single feature).</p>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/memberships/bulk")
@RequiredArgsConstructor
public class BulkMembershipController {

    private final BulkMembershipService service;
    private final PermissionService permissionService;
    private final ApiRateLimiter rateLimiter;

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresFeature(FeatureKey.BULK_IMPORT)
    public ResponseEntity<BulkMembershipPreviewResult> preview(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file) throws IOException {

        permissionService.requireFeature(principal, directoryId, FeatureKey.GROUP_MANAGE_MEMBERS);
        rateLimiter.check(principal.username(), "bulk-membership-preview");
        return ResponseEntity.ok(service.preview(directoryId, principal, file.getInputStream()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresFeature(FeatureKey.BULK_IMPORT)
    public ResponseEntity<BulkMembershipResult> apply(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file) throws IOException {

        permissionService.requireFeature(principal, directoryId, FeatureKey.GROUP_MANAGE_MEMBERS);
        rateLimiter.check(principal.username(), "bulk-membership");
        return ResponseEntity.ok(service.apply(directoryId, principal, file.getInputStream()));
    }
}
