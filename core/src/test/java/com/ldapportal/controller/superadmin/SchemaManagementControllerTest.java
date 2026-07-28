// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.dto.schema.SchemaPreviewSummary;
import com.ldapportal.dto.schema.SchemaUpdateResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.ldap.SchemaLdifService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice tests for {@link SchemaManagementController}: superadmin-only authz
 * on the coarse role gate, multipart preview, and apply-from-preview.
 */
@WebMvcTest(controllers = SchemaManagementController.class)
class SchemaManagementControllerTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SchemaLdifService schemaLdifService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private DirectoryConnectionRepository dirRepo;

    private final UUID dirId = UUID.randomUUID();

    private DirectoryConnection dir() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(dirId);
        dc.setDirectoryType(DirectoryType.ORACLE_UNIFIED_DIRECTORY);
        dc.setBaseDn("dc=example,dc=com");
        return dc;
    }

    private String base() {
        return "/api/v1/superadmin/directories/" + dirId + "/schema";
    }

    private MockMultipartFile ldifFile() {
        return new MockMultipartFile("file", "schema.ldif", "application/octet-stream",
                "dn: cn=schema\nchangetype: modify\n".getBytes());
    }

    @Test
    void preview_returns_summary_for_superadmin() throws Exception {
        when(dirRepo.findById(eq(dirId))).thenReturn(Optional.of(dir()));
        SchemaPreviewSummary summary = new SchemaPreviewSummary(
                "11111111-1111-1111-1111-111111111111", dirId,
                DirectoryType.ORACLE_UNIFIED_DIRECTORY, 1,
                new SchemaPreviewSummary.Counts(1, 0, 0, 0), List.of(), false);
        when(schemaLdifService.createPreview(any(), any(), any())).thenReturn(summary);

        mockMvc.perform(multipart(base() + "/import/preview")
                        .file(ldifFile())
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.blocking").value(false))
                .andExpect(jsonPath("$.counts.addNew").value(1));
    }

    @Test
    void preview_forbidden_for_non_superadmin() throws Exception {
        mockMvc.perform(multipart(base() + "/import/preview")
                        .file(ldifFile())
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void apply_returns_result_for_superadmin() throws Exception {
        when(dirRepo.findById(eq(dirId))).thenReturn(Optional.of(dir()));
        when(schemaLdifService.apply(any(), any(), any(), any()))
                .thenReturn(new SchemaUpdateResult(2, 0, List.of()));

        String previewId = "11111111-1111-1111-1111-111111111111";
        mockMvc.perform(post(base() + "/import/preview/" + previewId + "/apply")
                        .contentType("application/json")
                        .content("{}")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.failed").value(0));
    }
}
