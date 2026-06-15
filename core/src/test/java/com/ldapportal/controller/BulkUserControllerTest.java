// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.ApiRateLimiter;
import com.ldapportal.controller.directory.BulkUserController;
import com.ldapportal.dto.csv.BulkDeletePreviewResult;
import com.ldapportal.dto.csv.BulkDeletePreviewRow;
import com.ldapportal.dto.csv.BulkDeleteRequest;
import com.ldapportal.dto.csv.BulkDeleteResult;
import com.ldapportal.dto.csv.BulkDeleteRowResult;
import com.ldapportal.service.ApprovalWorkflowService;
import com.ldapportal.service.LdapOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BulkUserController.class)
class BulkUserControllerTest extends BaseControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean LdapOperationService    ldapService;
    @MockitoBean ApprovalWorkflowService approvalService;
    @MockitoBean ApiRateLimiter          rateLimiter;

    static final UUID DIR_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final String BASE_URL = "/api/v1/directories/" + DIR_ID + "/users";

    private MockMultipartFile filePart(String csv) {
        return new MockMultipartFile("file", "users.csv", "text/csv", csv.getBytes());
    }

    private MockMultipartFile requestPart(BulkDeleteRequest req) throws Exception {
        return new MockMultipartFile("request", "", "application/json",
                objectMapper.writeValueAsBytes(req));
    }

    @Test
    void previewBulkDelete_delegatesAndReturnsRows() throws Exception {
        given(ldapService.previewBulkDelete(eq(DIR_ID), any(), any(), any()))
                .willReturn(new BulkDeletePreviewResult(1, List.of(
                        new BulkDeletePreviewRow(1, "uid=a,ou=people,dc=example,dc=com",
                                BulkDeletePreviewRow.Disposition.WILL_DELETE, null))));

        mockMvc.perform(multipart(BASE_URL + "/bulk-delete/preview")
                        .file(filePart("dn\n\"uid=a,ou=people,dc=example,dc=com\"\n"))
                        .file(requestPart(new BulkDeleteRequest(null, null, null, true)))
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(1))
                .andExpect(jsonPath("$.rows[0].disposition").value("WILL_DELETE"));
    }

    @Test
    void bulkDelete_commitsDirectlyWithoutApprovalInterception() throws Exception {
        given(ldapService.bulkDeleteUsers(eq(DIR_ID), any(), any(), any()))
                .willReturn(new BulkDeleteResult(1, 1, 0, 0, List.of(
                        BulkDeleteRowResult.deleted(1, "uid=a,ou=people,dc=example,dc=com"))));

        mockMvc.perform(multipart(BASE_URL + "/bulk-delete")
                        .file(filePart("dn\n\"uid=a,ou=people,dc=example,dc=com\"\n"))
                        .file(requestPart(new BulkDeleteRequest(null, null, null, true)))
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));

        verify(ldapService).bulkDeleteUsers(eq(DIR_ID), any(), any(), any());
        // Unlike import/create, delete is never routed through the approval
        // workflow — a deliberate product decision.
        verify(approvalService, never()).checkAndSubmitForApproval(any(), any(), any(), any(), any());
    }
}
