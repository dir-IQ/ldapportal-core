// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.ApiRateLimiter;
import com.ldapportal.controller.directory.BulkGroupController;
import com.ldapportal.dto.csv.BulkImportRequest;
import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.entity.enums.ApprovalRequestType;
import com.ldapportal.service.ApprovalWorkflowService;
import com.ldapportal.service.LdapOperationService;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BulkGroupController.class)
class BulkGroupControllerTest extends BaseControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean LdapOperationService    ldapService;
    @MockitoBean ApprovalWorkflowService approvalService;
    @MockitoBean ApiRateLimiter          rateLimiter;

    static final UUID DIR_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final String BASE_URL = "/api/v1/directories/" + DIR_ID + "/groups";

    @Test
    void import_profileOnly_routesApprovalByProfileGroupTargetDn() throws Exception {
        // What the UI sends: the active profile, no parentDn.
        BulkImportRequest req = new BulkImportRequest(
                null, UUID.randomUUID(), null, null, null, true, null, List.of());
        given(ldapService.resolveBulkImportTargetDn(eq(DIR_ID), eq(req), eq(true)))
                .willReturn("ou=groups,dc=example,dc=com");
        given(approvalService.checkAndSubmitForApproval(any(), any(), any(), any(), any()))
                .willReturn(Optional.empty());
        given(ldapService.bulkImportGroups(eq(DIR_ID), any(), any(), any(), any(), any()))
                .willReturn(new BulkImportResult(1, 1, 0, 0, 0, List.of()));

        mockMvc.perform(multipart(BASE_URL + "/import")
                        .file(new MockMultipartFile("file", "groups.csv", "text/csv", "cn\ng1\n".getBytes()))
                        .file(new MockMultipartFile("request", "", "application/json",
                                objectMapper.writeValueAsBytes(req)))
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        // Regression: request.parentDn() (null here) used to be passed straight
        // through and NPE in profile resolution.
        verify(approvalService).checkAndSubmitForApproval(eq(DIR_ID), eq("ou=groups,dc=example,dc=com"),
                any(), eq(ApprovalRequestType.BULK_IMPORT), any());
    }
}
