// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.controller.BaseControllerTest;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChangelogRemediationController.class)
class ChangelogRemediationControllerTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ReplicationLinkService service;

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
}
