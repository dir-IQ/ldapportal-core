// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.dto.replication.FindingActionRequest;
import com.ldapportal.ldap.replication.reconcile.ReconciliationFindingService;
import com.ldapportal.ldap.replication.reconcile.ReconciliationService;
import com.ldapportal.service.ReplicationLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReconciliationController.class)
class ReconciliationControllerTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private ReconciliationService        reconciliationService;
    @MockitoBean private ReconciliationFindingService findingService;
    @MockitoBean private ReplicationLinkService       linkService;

    @Test
    void reconcileNow_returns202WithRunId() throws Exception {
        UUID linkId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        when(reconciliationService.trigger(eq(linkId), any(), any())).thenReturn(Optional.of(runId));

        mockMvc.perform(post("/api/v1/superadmin/replication-links/{id}/reconcile", linkId)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(runId.toString()));
    }

    @Test
    void reconcileNow_returns409WhenAlreadyRunning() throws Exception {
        UUID linkId = UUID.randomUUID();
        when(reconciliationService.trigger(eq(linkId), any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/superadmin/replication-links/{id}/reconcile", linkId)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isConflict());
    }

    @Test
    void applyFindings_returnsAppliedCount() throws Exception {
        UUID runId = UUID.randomUUID();
        when(findingService.apply(any(), eq(runId), any(), anyBoolean(), isNull())).thenReturn(2);

        var req = new FindingActionRequest(List.of(UUID.randomUUID(), UUID.randomUUID()), false, null);
        mockMvc.perform(post("/api/v1/superadmin/reconciliation-runs/{runId}/findings/apply", runId)
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(2));
    }

    @Test
    void reconcileNow_forbiddenForNonSuperadmin() throws Exception {
        mockMvc.perform(post("/api/v1/superadmin/replication-links/{id}/reconcile", UUID.randomUUID())
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }
}
