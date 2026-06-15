// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.csv.CsvColumnMappingDto;
import com.ldapportal.dto.csv.CreateCsvMappingTemplateRequest;
import com.ldapportal.dto.csv.CsvMappingTemplateDto;
import com.ldapportal.entity.CsvMappingTemplate;
import com.ldapportal.entity.CsvMappingTemplateEntry;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.exception.ConflictException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.repository.CsvMappingTemplateEntryRepository;
import com.ldapportal.repository.CsvMappingTemplateRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * CRUD operations for named CSV mapping templates (§7.1, §10.2).
 *
 * <p>Templates are scoped per directory connection and store column-to-attribute
 * mappings that can be reused across import/export operations.</p>
 *
 */
@Service
@RequiredArgsConstructor
public class CsvMappingTemplateService {

    private final CsvMappingTemplateRepository      templateRepo;
    private final CsvMappingTemplateEntryRepository entryRepo;
    private final DirectoryConnectionRepository     dirRepo;

    // ── Public API ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CsvMappingTemplateDto> listByDirectory(UUID directoryId,
                                                       AuthPrincipal principal) {
        loadDirectory(directoryId, principal);
        return templateRepo.findAllByDirectoryId(directoryId)
                .stream()
                .map(t -> toDto(t, entryRepo.findAllByTemplateId(t.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CsvMappingTemplateDto getById(UUID directoryId, UUID templateId,
                                         AuthPrincipal principal) {
        loadDirectory(directoryId, principal);
        CsvMappingTemplate template = findTemplate(templateId, directoryId, principal);
        return toDto(template, entryRepo.findAllByTemplateId(templateId));
    }

    @Transactional
    public CsvMappingTemplateDto create(UUID directoryId,
                                        CreateCsvMappingTemplateRequest req,
                                        AuthPrincipal principal) {
        DirectoryConnection dir = loadDirectory(directoryId, principal);

        if (templateRepo.existsByDirectoryIdAndName(directoryId, req.name())) {
            throw new ConflictException(
                    "Template name '" + req.name() + "' already exists for this directory");
        }

        CsvMappingTemplate template = new CsvMappingTemplate();
        template.setDirectory(dir);
        template.setName(req.name());
        template.setTargetKeyAttribute(
                req.targetKeyAttribute() != null ? req.targetKeyAttribute() : "uid");
        template.setConflictHandling(
                req.conflictHandling() != null ? req.conflictHandling() : ConflictHandling.SKIP);
        template.setObjectClass(req.objectClass());
        template.setSkipHeaderRow(req.skipHeaderRow() != null ? req.skipHeaderRow() : true);
        template = templateRepo.save(template);

        List<CsvMappingTemplateEntry> entries = saveEntries(template, req.entries());
        return toDto(template, entries);
    }

    @Transactional
    public CsvMappingTemplateDto update(UUID directoryId, UUID templateId,
                                        CreateCsvMappingTemplateRequest req,
                                        AuthPrincipal principal) {
        loadDirectory(directoryId, principal);
        CsvMappingTemplate template = findTemplate(templateId, directoryId, principal);

        if (!template.getName().equals(req.name()) &&
                templateRepo.existsByDirectoryIdAndName(directoryId, req.name())) {
            throw new ConflictException(
                    "Template name '" + req.name() + "' already exists for this directory");
        }

        template.setName(req.name());
        if (req.targetKeyAttribute() != null) {
            template.setTargetKeyAttribute(req.targetKeyAttribute());
        }
        if (req.conflictHandling() != null) {
            template.setConflictHandling(req.conflictHandling());
        }
        if (req.objectClass() != null) {
            template.setObjectClass(req.objectClass());
        }
        if (req.skipHeaderRow() != null) {
            template.setSkipHeaderRow(req.skipHeaderRow());
        }
        template = templateRepo.save(template);

        entryRepo.deleteAllByTemplateId(templateId);
        List<CsvMappingTemplateEntry> entries = saveEntries(template, req.entries());
        return toDto(template, entries);
    }

    @Transactional
    public void delete(UUID directoryId, UUID templateId, AuthPrincipal principal) {
        loadDirectory(directoryId, principal);
        findTemplate(templateId, directoryId, principal); // validates access
        entryRepo.deleteAllByTemplateId(templateId);
        templateRepo.deleteById(templateId);
    }

    // ── Package-visible accessors used by LdapOperationService ───────────────

    CsvMappingTemplate loadTemplate(UUID templateId, UUID directoryId,
                                    AuthPrincipal principal) {
        return findTemplate(templateId, directoryId, principal);
    }

    List<CsvMappingTemplateEntry> loadEntries(UUID templateId) {
        return entryRepo.findAllByTemplateId(templateId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private DirectoryConnection loadDirectory(UUID directoryId, AuthPrincipal principal) {
        return dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "DirectoryConnection", directoryId));
    }

    private CsvMappingTemplate findTemplate(UUID templateId, UUID directoryId,
                                             AuthPrincipal principal) {
        CsvMappingTemplate template = templateRepo.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "CsvMappingTemplate", templateId));
        if (!template.getDirectory().getId().equals(directoryId)) {
            throw new ResourceNotFoundException("CsvMappingTemplate", templateId);
        }
        return template;
    }

    private List<CsvMappingTemplateEntry> saveEntries(CsvMappingTemplate template,
                                                       List<CsvColumnMappingDto> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        return mappings.stream()
                .map(m -> {
                    CsvMappingTemplateEntry entry = new CsvMappingTemplateEntry();
                    entry.setTemplate(template);
                    entry.setCsvColumnName(m.csvColumn());
                    entry.setLdapAttribute(m.ignored() ? null : m.ldapAttribute());
                    entry.setIgnored(m.ignored());
                    return entryRepo.save(entry);
                })
                .toList();
    }

    private CsvMappingTemplateDto toDto(CsvMappingTemplate t,
                                         List<CsvMappingTemplateEntry> entries) {
        List<CsvColumnMappingDto> entryDtos = entries.stream()
                .map(e -> new CsvColumnMappingDto(
                        e.getCsvColumnName(), e.getLdapAttribute(), e.isIgnored()))
                .toList();
        return new CsvMappingTemplateDto(
                t.getId(),
                t.getDirectory().getId(),
                t.getName(),
                t.getObjectClass(),
                t.getTargetKeyAttribute(),
                t.getConflictHandling(),
                t.isSkipHeaderRow(),
                entryDtos,
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
