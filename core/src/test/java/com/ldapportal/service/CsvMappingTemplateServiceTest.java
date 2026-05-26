// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CsvMappingTemplateServiceTest {

    @Mock private CsvMappingTemplateRepository      templateRepo;
    @Mock private CsvMappingTemplateEntryRepository entryRepo;
    @Mock private DirectoryConnectionRepository     dirRepo;

    private CsvMappingTemplateService service;

    private final UUID dirId      = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();

    private AuthPrincipal adminPrincipal;
    private AuthPrincipal superadminPrincipal;

    @BeforeEach
    void setUp() {
        service = new CsvMappingTemplateService(templateRepo, entryRepo, dirRepo);
        adminPrincipal      = new AuthPrincipal(PrincipalType.ADMIN,      UUID.randomUUID(), "admin");
        superadminPrincipal = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "superadmin");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DirectoryConnection mockDirectory() {
        DirectoryConnection dir = new DirectoryConnection();
        dir.setId(dirId);
        return dir;
    }

    private CsvMappingTemplate mockTemplate(DirectoryConnection dir) {
        CsvMappingTemplate t = new CsvMappingTemplate();
        t.setId(templateId);
        t.setDirectory(dir);
        t.setName("My Template");
        t.setTargetKeyAttribute("uid");
        t.setConflictHandling(ConflictHandling.SKIP);
        return t;
    }

    // ── listByDirectory ───────────────────────────────────────────────────────

    @Test
    void listByDirectory_returnsTemplatesForDirectory() {
        DirectoryConnection dir = mockDirectory();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir));
        CsvMappingTemplate t = mockTemplate(dir);
        when(templateRepo.findAllByDirectoryId(dirId)).thenReturn(List.of(t));
        when(entryRepo.findAllByTemplateId(templateId)).thenReturn(List.of());

        List<CsvMappingTemplateDto> result = service.listByDirectory(dirId, adminPrincipal);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("My Template");
        assertThat(result.get(0).directoryId()).isEqualTo(dirId);
    }

    @Test
    void listByDirectory_superadminCanAccessAnyDirectory() {
        DirectoryConnection dir = mockDirectory();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir));
        when(templateRepo.findAllByDirectoryId(dirId)).thenReturn(List.of());

        List<CsvMappingTemplateDto> result = service.listByDirectory(dirId, superadminPrincipal);

        assertThat(result).isEmpty();
    }

    @Test
    void listByDirectory_unknownDirectory_throwsNotFound() {
        when(dirRepo.findById(dirId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listByDirectory(dirId, adminPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── getById ───────────────────────────────────────────────────────────────

    @Test
    void getById_returnsTemplateWithEntries() {
        DirectoryConnection dir = mockDirectory();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir));
        CsvMappingTemplate t = mockTemplate(dir);
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(t));
        CsvMappingTemplateEntry entry = new CsvMappingTemplateEntry();
        entry.setId(UUID.randomUUID());
        entry.setCsvColumnName("First Name");
        entry.setLdapAttribute("givenName");
        entry.setIgnored(false);
        when(entryRepo.findAllByTemplateId(templateId)).thenReturn(List.of(entry));

        CsvMappingTemplateDto dto = service.getById(dirId, templateId, adminPrincipal);

        assertThat(dto.name()).isEqualTo("My Template");
        assertThat(dto.entries()).hasSize(1);
        assertThat(dto.entries().get(0).csvColumn()).isEqualTo("First Name");
        assertThat(dto.entries().get(0).ldapAttribute()).isEqualTo("givenName");
    }

    @Test
    void getById_templateBelongsToDifferentDirectory_throwsNotFound() {
        DirectoryConnection dir = mockDirectory();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir));

        CsvMappingTemplate t = mockTemplate(dir);
        UUID otherId = UUID.randomUUID();
        t.getDirectory().setId(otherId); // belongs to a different directory
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.getById(dirId, templateId, adminPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_savesTemplateAndEntries() {
        DirectoryConnection dir = mockDirectory();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir));
        when(templateRepo.existsByDirectoryIdAndName(dirId, "New Template")).thenReturn(false);

        CsvMappingTemplate saved = mockTemplate(dir);
        saved.setName("New Template");
        when(templateRepo.save(any())).thenReturn(saved);

        CsvMappingTemplateEntry savedEntry = new CsvMappingTemplateEntry();
        savedEntry.setId(UUID.randomUUID());
        savedEntry.setCsvColumnName("email");
        savedEntry.setLdapAttribute("mail");
        savedEntry.setIgnored(false);
        when(entryRepo.save(any())).thenReturn(savedEntry);

        List<CsvColumnMappingDto> mappings = List.of(
                new CsvColumnMappingDto("email", "mail", false));
        CreateCsvMappingTemplateRequest req = new CreateCsvMappingTemplateRequest(
                "New Template", "inetOrgPerson", "uid", ConflictHandling.OVERWRITE, true, mappings);

        CsvMappingTemplateDto result = service.create(dirId, req, adminPrincipal);

        assertThat(result.name()).isEqualTo("New Template");
        ArgumentCaptor<CsvMappingTemplate> captor =
                ArgumentCaptor.forClass(CsvMappingTemplate.class);
        verify(templateRepo).save(captor.capture());
        assertThat(captor.getValue().getTargetKeyAttribute()).isEqualTo("uid");
        assertThat(captor.getValue().getConflictHandling()).isEqualTo(ConflictHandling.OVERWRITE);
        verify(entryRepo, times(1)).save(any());
    }

    @Test
    void create_defaultsAppliedWhenFieldsNull() {
        DirectoryConnection dir = mockDirectory();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir));
        when(templateRepo.existsByDirectoryIdAndName(dirId, "T")).thenReturn(false);
        CsvMappingTemplate saved = mockTemplate(dir);
        when(templateRepo.save(any())).thenReturn(saved);

        CreateCsvMappingTemplateRequest req = new CreateCsvMappingTemplateRequest(
                "T", null, null, null, null, List.of());

        service.create(dirId, req, adminPrincipal);

        ArgumentCaptor<CsvMappingTemplate> captor =
                ArgumentCaptor.forClass(CsvMappingTemplate.class);
        verify(templateRepo).save(captor.capture());
        assertThat(captor.getValue().getTargetKeyAttribute()).isEqualTo("uid");
        assertThat(captor.getValue().getConflictHandling()).isEqualTo(ConflictHandling.SKIP);
    }

    @Test
    void create_duplicateName_throwsConflict() {
        DirectoryConnection dir = mockDirectory();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir));
        when(templateRepo.existsByDirectoryIdAndName(dirId, "Existing")).thenReturn(true);

        CreateCsvMappingTemplateRequest req = new CreateCsvMappingTemplateRequest(
                "Existing", null, null, null, null, List.of());

        assertThatThrownBy(() -> service.create(dirId, req, adminPrincipal))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Existing");
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_replacesEntriesAndUpdatesFields() {
        DirectoryConnection dir = mockDirectory();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir));
        CsvMappingTemplate existing = mockTemplate(dir);
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(existing));
        when(templateRepo.existsByDirectoryIdAndName(dirId, "Renamed")).thenReturn(false);
        when(templateRepo.save(any())).thenReturn(existing);

        CsvMappingTemplateEntry newEntry = new CsvMappingTemplateEntry();
        newEntry.setId(UUID.randomUUID());
        newEntry.setCsvColumnName("sn");
        newEntry.setLdapAttribute("sn");
        newEntry.setIgnored(false);
        when(entryRepo.save(any())).thenReturn(newEntry);

        CreateCsvMappingTemplateRequest req = new CreateCsvMappingTemplateRequest(
                "Renamed", "inetOrgPerson", "sAMAccountName", ConflictHandling.OVERWRITE, true,
                List.of(new CsvColumnMappingDto("sn", "sn", false)));

        CsvMappingTemplateDto result = service.update(dirId, templateId, req, adminPrincipal);

        verify(entryRepo).deleteAllByTemplateId(templateId);
        assertThat(result.entries()).hasSize(1);
    }

    @Test
    void update_renameToDuplicateName_throwsConflict() {
        DirectoryConnection dir = mockDirectory();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir));
        CsvMappingTemplate existing = mockTemplate(dir);
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(existing));
        when(templateRepo.existsByDirectoryIdAndName(dirId, "Other")).thenReturn(true);

        CreateCsvMappingTemplateRequest req = new CreateCsvMappingTemplateRequest(
                "Other", null, null, null, null, List.of());

        assertThatThrownBy(() -> service.update(dirId, templateId, req, adminPrincipal))
                .isInstanceOf(ConflictException.class);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removesEntriesAndTemplate() {
        DirectoryConnection dir = mockDirectory();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir));
        CsvMappingTemplate t = mockTemplate(dir);
        when(templateRepo.findById(templateId)).thenReturn(Optional.of(t));

        service.delete(dirId, templateId, adminPrincipal);

        verify(entryRepo).deleteAllByTemplateId(templateId);
        verify(templateRepo).deleteById(templateId);
    }

    @Test
    void delete_templateNotFound_throwsNotFound() {
        DirectoryConnection dir = mockDirectory();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir));
        when(templateRepo.findById(templateId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(dirId, templateId, adminPrincipal))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
