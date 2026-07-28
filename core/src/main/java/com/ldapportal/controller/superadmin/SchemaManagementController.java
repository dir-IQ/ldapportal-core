// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.RequiresSuperadminPermission;
import com.ldapportal.dto.schema.ApplySchemaPreviewRequest;
import com.ldapportal.dto.schema.SchemaPreviewSummary;
import com.ldapportal.dto.schema.SchemaUpdateResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.entity.enums.SuperadminPermission;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.SchemaLdifService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.AuditService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Superadmin-only schema management: apply directory-schema changes
 * (attributeTypes / objectClasses) from an uploaded LDIF, with a mandatory
 * server-side preview before anything is written, and a full-schema export for
 * snapshotting first.
 *
 * <pre>
 *   POST /api/v1/superadmin/directories/{directoryId}/schema/import/preview
 *   POST /api/v1/superadmin/directories/{directoryId}/schema/import/preview/{previewId}/apply
 *   GET  /api/v1/superadmin/directories/{directoryId}/schema/export
 * </pre>
 *
 * <p>Gated by both the coarse {@code hasRole('SUPERADMIN')} URL rule and the
 * fine-grained {@link SuperadminPermission#MANAGE_SCHEMA} capability. v1
 * supports OpenLDAP ({@code cn=config}) and OpenDJ/OUD ({@code cn=schema}).</p>
 */
@RestController
@RequestMapping("/api/v1/superadmin/directories/{directoryId}/schema")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiresSuperadminPermission(SuperadminPermission.MANAGE_SCHEMA)
@RequiredArgsConstructor
public class SchemaManagementController {

    private final SchemaLdifService schemaLdifService;
    private final AuditService auditService;
    private final DirectoryConnectionRepository dirRepo;

    @PostMapping("/import/preview")
    public SchemaPreviewSummary preview(@PathVariable UUID directoryId,
                                        @AuthenticationPrincipal AuthPrincipal principal,
                                        @RequestParam("file") MultipartFile file) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId);
        return schemaLdifService.createPreview(dc, file.getInputStream(), principal.id());
    }

    @PostMapping("/import/preview/{previewId}/apply")
    public SchemaUpdateResult apply(@PathVariable UUID directoryId,
                                    @PathVariable UUID previewId,
                                    @AuthenticationPrincipal AuthPrincipal principal,
                                    @RequestBody(required = false) ApplySchemaPreviewRequest body) {
        DirectoryConnection dc = loadDirectory(directoryId);
        SchemaUpdateResult result = schemaLdifService.apply(previewId, principal.id(), dc, body);

        auditService.record(principal, directoryId, AuditAction.SCHEMA_UPDATE, "cn=schema",
                Map.of("applied", result.applied(),
                        "failed", result.failed(),
                        "directoryType", dc.getDirectoryType().name(),
                        "source", "preview"));

        return result;
    }

    @GetMapping("/export")
    public void export(@PathVariable UUID directoryId,
                       HttpServletResponse response) throws IOException {
        DirectoryConnection dc = loadDirectory(directoryId);
        String ldif = schemaLdifService.exportSchemaLdif(dc);

        response.setContentType("application/ldif");
        response.setHeader("Content-Disposition", "attachment; filename=\"schema.ldif\"");
        response.getOutputStream().write(ldif.getBytes(StandardCharsets.UTF_8));
    }

    private DirectoryConnection loadDirectory(UUID directoryId) {
        return dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId));
    }
}
