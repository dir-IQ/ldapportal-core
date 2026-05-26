// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.auth.ApiRateLimiter;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.dto.csv.BulkImportPreviewResult;
import com.ldapportal.dto.csv.BulkImportRequest;
import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.enums.ApprovalRequestType;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.service.ApprovalWorkflowService;
import com.ldapportal.service.LdapOperationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Bulk CSV import and export endpoints for a specific directory.
 *
 * <pre>
 *   POST /api/directories/{directoryId}/users/import — multipart CSV upload
 *   GET  /api/directories/{directoryId}/users/export — CSV file download
 * </pre>
 *
 * <h3>Import</h3>
 * <p>Accepts {@code multipart/form-data} with two parts:</p>
 * <ul>
 *   <li>{@code file} — the CSV file (UTF-8, first row = column headers)</li>
 *   <li>{@code request} — JSON object matching {@link BulkImportRequest}
 *       (Content-Type: application/json)</li>
 * </ul>
 *
 * <h3>Export</h3>
 * <p>Returns {@code text/csv} with a {@code Content-Disposition: attachment}
 * header.  Multi-valued LDAP attributes are serialised as pipe-separated
 * strings within a single cell.</p>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/users")
@RequiredArgsConstructor
public class BulkUserController {

    private final LdapOperationService service;
    private final ApprovalWorkflowService approvalService;
    private final ApiRateLimiter       rateLimiter;

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresFeature(FeatureKey.BULK_IMPORT)
    public ResponseEntity<BulkImportPreviewResult> previewImport(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file,
            @RequestPart("request") @Valid BulkImportRequest request) throws IOException {

        rateLimiter.check(principal.username(), "bulk-import-preview");
        BulkImportPreviewResult result = service.previewBulkImport(
                directoryId, principal, file.getInputStream(), request);
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresFeature(FeatureKey.BULK_IMPORT)
    public ResponseEntity<?> importUsers(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestPart("file") MultipartFile file,
            @RequestPart("request") @Valid BulkImportRequest request) throws IOException {

        rateLimiter.check(principal.username(), "bulk-import");

        // Check if approval is required — handles both profiled and unprovisioned OUs
        Map<String, Object> payload = Map.of(
                "request", request,
                "csvContent", java.util.Base64.getEncoder().encodeToString(file.getBytes()));
        Optional<PendingApproval> pendingApproval = approvalService.checkAndSubmitForApproval(
                directoryId, request.parentDn(), principal,
                ApprovalRequestType.BULK_IMPORT, payload);
        if (pendingApproval.isPresent()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of(
                            "message", "Bulk import submitted for approval",
                            "approvalId", pendingApproval.get().getId()));
        }

        BulkImportResult result = service.bulkImportUsers(
                directoryId, principal, file.getInputStream(), request);
        return ResponseEntity.ok(result);
    }

    /**
     * Exports users matching the given filter as a CSV file.
     *
     * @param filter     LDAP filter (optional; defaults to {@code (objectClass=*)})
     * @param baseDn     search base DN (optional; defaults to directory base DN)
     * @param attributes comma-separated attribute names to include as CSV columns;
     *                   when empty and {@code templateId} is set, the template's
     *                   attribute list is used; otherwise all attributes are returned
     * @param templateId optional saved template to derive the attribute list from
     */
    @GetMapping(value = "/export", produces = "text/csv")
    @RequiresFeature(FeatureKey.BULK_EXPORT)
    public ResponseEntity<byte[]> exportUsers(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String baseDn,
            @RequestParam(required = false, defaultValue = "") String attributes,
            @RequestParam(required = false) UUID templateId) throws IOException {

        rateLimiter.check(principal.username(), "bulk-export");
        List<String> attrList = attributes.isBlank()
                ? List.of()
                : Arrays.stream(attributes.split(",")).map(String::trim).toList();

        byte[] csv = service.bulkExportUsers(
                directoryId, principal, filter, baseDn, attrList, templateId);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"users.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }
}
