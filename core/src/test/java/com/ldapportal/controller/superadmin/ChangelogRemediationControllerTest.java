// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.dto.replication.ChangelogTestResult;
import com.ldapportal.service.ChangelogTestService;
import com.ldapportal.service.ReplicationLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChangelogRemediationController.class)
class ChangelogRemediationControllerTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ReplicationLinkService service;
    @MockitoBean private ChangelogTestService   testService;

    @Test
    void reseed_asSuperadmin_returns200_andCallsService() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/superadmin/replication-links/{id}/changelog/reseed", id)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk());
        verify(service).reseedChangelogCursor(any(), eq(id));
    }

    @Test
    void rewind_withChangeNumber_returns200_andCallsService() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/superadmin/replication-links/{id}/changelog/rewind", id)
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("changeNumber", 4242))))
                .andExpect(status().isOk());
        verify(service).rewindChangelogCursor(any(), eq(id), eq(4242L));
    }

    @Test
    void rewind_missingChangeNumber_returns400() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/superadmin/replication-links/{id}/changelog/rewind", id)
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reEnable_asSuperadmin_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/superadmin/replication-links/{id}/changelog/re-enable", id)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk());
        verify(service).reEnableChangelogPoll(any(), eq(id));
    }

    @Test
    void reseed_asAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/superadmin/replication-links/{id}/changelog/reseed", UUID.randomUUID())
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void testChangelog_existingLink_resolvesViaGetLink_404WhenMissing() throws Exception {
        // The existing-link variant resolves the link through getLink (which
        // 404s if absent); pins that routing + the not-found mapping.
        UUID id = UUID.randomUUID();
        when(service.getLink(id)).thenThrow(
                new com.ldapportal.exception.ResourceNotFoundException("ReplicationLink", id.toString()));

        mockMvc.perform(post("/api/v1/superadmin/replication-links/{id}/test-changelog", id)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void testChangelog_preSave_withBody_returnsResult() throws Exception {
        UUID dir = UUID.randomUUID();
        when(testService.test(eq(dir), any()))
                .thenReturn(new ChangelogTestResult(false, "no firstChangeNumber", 8L, null, null));

        mockMvc.perform(post("/api/v1/superadmin/replication-links/test-changelog")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("sourceDirectoryId", dir.toString(), "changelogBaseDn", "cn=changelog"))))
                .andExpect(status().isOk());
        verify(testService).test(eq(dir), eq("cn=changelog"));
    }

    @Test
    void testChangelog_preSave_missingDirectoryId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/superadmin/replication-links/test-changelog")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
