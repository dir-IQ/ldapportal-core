// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.dto.replication.ReplicationLinkRequest;
import com.ldapportal.service.ReplicationLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReplicationLinkController.class)
class ReplicationLinkControllerTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ReplicationLinkService service;

    /**
     * The bidirectional-rejection guard must surface as a 400 through
     * the controller layer, not just inside the service. Pinning the
     * {@code IllegalArgumentException → 400} mapping via
     * {@code GlobalExceptionHandler} so the operator-facing API contract
     * (and the R5 Playwright smoke) can rely on it.
     */
    @Test
    void create_reverseOfExistingLink_returns400() throws Exception {
        when(service.createLink(any(), any())).thenThrow(new IllegalArgumentException(
                "Would create reverse of existing link " + UUID.randomUUID()
              + " — bidirectional configurations require origin-stamped events, not in v1."));

        ReplicationLinkRequest req = new ReplicationLinkRequest(
                "A → B", UUID.randomUUID(), UUID.randomUUID(),
                null, null, true, false, List.of());

        mockMvc.perform(post("/api/v1/superadmin/replication-links")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_asAdmin_returns403() throws Exception {
        ReplicationLinkRequest req = new ReplicationLinkRequest(
                "A → B", UUID.randomUUID(), UUID.randomUUID(),
                null, null, true, false, List.of());

        mockMvc.perform(post("/api/v1/superadmin/replication-links")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }
}
