// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.DirectoryId;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.dto.csv.CreateCsvMappingTemplateRequest;
import com.ldapportal.dto.csv.CsvMappingTemplateDto;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.service.CsvMappingTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
 * CRUD endpoints for named CSV mapping templates scoped to a directory.
 *
 * <pre>
 *   GET    /api/directories/{directoryId}/csv-templates              — list
 *   POST   /api/directories/{directoryId}/csv-templates              — create
 *   GET    /api/directories/{directoryId}/csv-templates/{templateId} — get
 *   PUT    /api/directories/{directoryId}/csv-templates/{templateId} — replace
 *   DELETE /api/directories/{directoryId}/csv-templates/{templateId} — delete
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/directories/{directoryId}/csv-templates")
@RequiredArgsConstructor
public class CsvMappingTemplateController {

    private final CsvMappingTemplateService service;

    @GetMapping
    @RequiresFeature(FeatureKey.CSV_TEMPLATE_MANAGE)
    public List<CsvMappingTemplateDto> list(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.listByDirectory(directoryId, principal);
    }

    @PostMapping
    @RequiresFeature(FeatureKey.CSV_TEMPLATE_MANAGE)
    public ResponseEntity<CsvMappingTemplateDto> create(
            @DirectoryId @PathVariable UUID directoryId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateCsvMappingTemplateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(directoryId, req, principal));
    }

    @GetMapping("/{templateId}")
    @RequiresFeature(FeatureKey.CSV_TEMPLATE_MANAGE)
    public CsvMappingTemplateDto get(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID templateId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return service.getById(directoryId, templateId, principal);
    }

    @PutMapping("/{templateId}")
    @RequiresFeature(FeatureKey.CSV_TEMPLATE_MANAGE)
    public CsvMappingTemplateDto update(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID templateId,
            @AuthenticationPrincipal AuthPrincipal principal,
            @Valid @RequestBody CreateCsvMappingTemplateRequest req) {
        return service.update(directoryId, templateId, req, principal);
    }

    @DeleteMapping("/{templateId}")
    @RequiresFeature(FeatureKey.CSV_TEMPLATE_MANAGE)
    public ResponseEntity<Void> delete(
            @DirectoryId @PathVariable UUID directoryId,
            @PathVariable UUID templateId,
            @AuthenticationPrincipal AuthPrincipal principal) {
        service.delete(directoryId, templateId, principal);
        return ResponseEntity.noContent().build();
    }
}
