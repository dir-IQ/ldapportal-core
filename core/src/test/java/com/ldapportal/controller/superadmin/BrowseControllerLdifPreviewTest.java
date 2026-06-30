// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.dto.ldap.LdifImportResult;
import com.ldapportal.dto.ldap.LdifPreviewOp;
import com.ldapportal.dto.ldap.LdifPreviewPage;
import com.ldapportal.dto.ldap.LdifPreviewRow;
import com.ldapportal.dto.ldap.LdifPreviewSummary;
import com.ldapportal.dto.ldap.LdifPreviewSummary.OpCounts;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.ldap.IntegrityCheckService;
import com.ldapportal.ldap.LdapBrowseService;
import com.ldapportal.ldap.LdapSchemaService;
import com.ldapportal.ldap.LdifPreviewService;
import com.ldapportal.ldap.LdifService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice tests for the LDIF preview endpoints on {@link BrowseController}:
 * superadmin-only authz, multipart preview, paging, and apply-from-preview.
 */
@WebMvcTest(controllers = BrowseController.class)
class BrowseControllerLdifPreviewTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private LdapBrowseService browseService;
    @MockitoBean private LdapSchemaService schemaService;
    @MockitoBean private LdifService ldifService;
    @MockitoBean private LdifPreviewService ldifPreviewService;
    @MockitoBean private IntegrityCheckService integrityCheckService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private DirectoryConnectionRepository dirRepo;

    private final UUID dirId = UUID.randomUUID();

    private DirectoryConnection dir() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(dirId);
        dc.setBaseDn("dc=example,dc=com");
        return dc;
    }

    private LdifPreviewSummary sampleSummary() {
        LdifPreviewRow row = new LdifPreviewRow(1, "uid=bob,dc=example,dc=com",
                LdifPreviewOp.ADD, List.of("inetOrgPerson"), 5, null, null, List.of(), true);
        return new LdifPreviewSummary("11111111-1111-1111-1111-111111111111", 1,
                new OpCounts(1, 0, 0, 0, 0, 0), 0, 0, false,
                new LdifPreviewPage(List.of(row), 0, 50, 1), 1, false,
                1, 0, "dc=example,dc=com");
    }

    @Test
    void preview_returnsSummary_forSuperadmin() throws Exception {
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir()));
        when(ldifPreviewService.createPreview(any(), any(), eq(ConflictHandling.SKIP), any()))
                .thenReturn(sampleSummary());

        var file = new MockMultipartFile("file", "x.ldif", "text/plain", "dn: x\n".getBytes());
        mockMvc.perform(multipart("/api/v1/superadmin/directories/{id}/browse/import/ldif/preview", dirId)
                        .file(file)
                        .param("conflictHandling", "SKIP")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.countsByOp.add").value(1))
                .andExpect(jsonPath("$.page0.rows[0].op").value("ADD"));
    }

    @Test
    void preview_forbiddenForNonSuperadmin() throws Exception {
        var file = new MockMultipartFile("file", "x.ldif", "text/plain", "dn: x\n".getBytes());
        mockMvc.perform(multipart("/api/v1/superadmin/directories/{id}/browse/import/ldif/preview", dirId)
                        .file(file)
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void page_returnsFilteredRows() throws Exception {
        UUID previewId = UUID.randomUUID();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir()));
        LdifPreviewRow row = new LdifPreviewRow(2, "uid=ann,dc=example,dc=com",
                LdifPreviewOp.ADD, List.of(), 3, null, null, List.of(), false);
        when(ldifPreviewService.page(eq(previewId), any(), eq("ADD"), any(), eq(0), eq(50)))
                .thenReturn(new LdifPreviewPage(List.of(row), 0, 50, 1));

        mockMvc.perform(get("/api/v1/superadmin/directories/{id}/browse/import/ldif/preview/{pid}", dirId, previewId)
                        .param("op", "ADD")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFiltered").value(1))
                .andExpect(jsonPath("$.rows[0].rowNumber").value(2));
    }

    @Test
    void apply_executesAndReturnsResult() throws Exception {
        UUID previewId = UUID.randomUUID();
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir()));
        when(ldifPreviewService.conflictOf(eq(previewId), any())).thenReturn(ConflictHandling.SKIP);
        when(ldifPreviewService.apply(eq(previewId), any(), any(),
                        org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenReturn(new LdifImportResult(3, 1, 0, 0, List.of()));

        mockMvc.perform(post("/api/v1/superadmin/directories/{id}/browse/import/ldif/preview/{pid}/apply",
                        dirId, previewId)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(3))
                .andExpect(jsonPath("$.updated").value(1));
    }
}
