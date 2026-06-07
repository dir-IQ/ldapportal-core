// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.controller.superadmin.SyncLinkController;
import com.ldapportal.controller.superadmin.SyncSetController;
import com.ldapportal.dto.sync.MembershipResponse;
import com.ldapportal.dto.sync.RecomputeKeyRequest;
import com.ldapportal.dto.sync.SyncLinkRequest;
import com.ldapportal.dto.sync.SyncLinkResponse;
import com.ldapportal.dto.sync.SyncSetRequest;
import com.ldapportal.dto.sync.SyncSetResponse;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SyncCaptureMode;
import com.ldapportal.service.SyncConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({SyncLinkController.class, SyncSetController.class})
class SyncControllerTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean SyncConfigService service;

    private static final UUID LINK = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SET = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String LINKS = "/api/v1/superadmin/sync/links";
    private static final String SETS = "/api/v1/superadmin/sync/sets";

    private SyncLinkRequest linkReq() {
        return new SyncLinkRequest("src->dst", UUID.randomUUID(), UUID.randomUUID(), true,
                SyncCaptureMode.APP_INTERCEPT, null, null);
    }

    private SyncLinkResponse linkResp() {
        return new SyncLinkResponse(LINK, "src->dst", UUID.randomUUID(), UUID.randomUUID(), true,
                SyncCaptureMode.APP_INTERCEPT, null, null, null, null, null, null, null,
                OffsetDateTime.now(), OffsetDateTime.now(), 0L);
    }

    private SyncSetRequest setReq() {
        return new SyncSetRequest(LINK, "people", "ou=people,dc=src", null, null, "ou=Users,dc=dst",
                "(objectClass=inetOrgPerson)", null, null, null, null, null, true);
    }

    private SyncSetResponse setResp() {
        return new SyncSetResponse(SET, LINK, "people", "ou=people,dc=src", null, null, "ou=Users,dc=dst",
                null, null, null, null, null, null, null, true, OffsetDateTime.now(), OffsetDateTime.now(), 0L,
                java.util.Map.of());
    }

    // ── authz ──────────────────────────────────────────────────────────────────

    @Test
    void listLinks_superadmin_returns200() throws Exception {
        given(service.listLinks()).willReturn(List.of(linkResp()));
        mockMvc.perform(get(LINKS).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("src->dst"));
    }

    @Test
    void createLink_adminRole_returns403() throws Exception {
        mockMvc.perform(post(LINKS).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkReq())))
                .andExpect(status().isForbidden());
    }

    @Test
    void createLink_anonymous_returns401() throws Exception {
        mockMvc.perform(post(LINKS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkReq())))
                .andExpect(status().isUnauthorized());
    }

    // ── CRUD + validation mapping ──────────────────────────────────────────────

    @Test
    void createLink_superadmin_returns201() throws Exception {
        given(service.createLink(any())).willReturn(linkResp());
        mockMvc.perform(post(LINKS).with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkReq())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(LINK.toString()));
    }

    @Test
    void createLink_serviceRejects_returns400() throws Exception {
        willThrow(new IllegalArgumentException("Source and target directories must differ"))
                .given(service).createLink(any());
        mockMvc.perform(post(LINKS).with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(linkReq())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createLink_blankDisplayName_returns400() throws Exception {
        SyncLinkRequest bad = new SyncLinkRequest("", UUID.randomUUID(), UUID.randomUUID(), true, null, null, null);
        mockMvc.perform(post(LINKS).with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSet_superadmin_returns201() throws Exception {
        given(service.createSet(any())).willReturn(setResp());
        mockMvc.perform(post(SETS).with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(setReq())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("people"));
    }

    @Test
    void deleteSet_adminRole_returns403() throws Exception {
        mockMvc.perform(delete(SETS + "/" + SET).with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    // ── inventory + triggers ────────────────────────────────────────────────────

    @Test
    void reconcile_superadmin_returnsCount() throws Exception {
        given(service.reconcileNow(SET)).willReturn(7);
        mockMvc.perform(post(SETS + "/" + SET + "/reconcile").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enumerated").value(7));
    }

    @Test
    void recompute_superadmin_returns202() throws Exception {
        mockMvc.perform(post(SETS + "/" + SET + "/recompute").with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecomputeKeyRequest("uid=a,dc=x"))))
                .andExpect(status().isAccepted());
    }

    @Test
    void memberships_superadmin_returns200_withTopLevelPageEnvelope() throws Exception {
        MembershipResponse row = new MembershipResponse(SET, "id-1", "uid=a,dc=src", "uid=a,dc=dst",
                MembershipState.APPLIED, null, null, null);
        given(service.listMemberships(any(), any(), any(), any()))
                .willReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 50), 120));
        mockMvc.perform(get(SETS + "/" + SET + "/memberships").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                // Stable top-level envelope (not Spring's nested page metadata).
                .andExpect(jsonPath("$.content[0].identity").value("id-1"))
                .andExpect(jsonPath("$.totalElements").value(120))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(50));
    }

    @Test
    void dismissMembership_superadmin_returns204() throws Exception {
        mockMvc.perform(delete(SETS + "/" + SET + "/memberships/abc-123").with(authentication(superadminAuth())))
                .andExpect(status().isNoContent());
    }
}
