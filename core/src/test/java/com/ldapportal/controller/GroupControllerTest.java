// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.controller.directory.GroupController;
import com.ldapportal.dto.ldap.CreateEntryRequest;
import com.ldapportal.dto.ldap.AttributeModification;
import com.ldapportal.dto.ldap.LdapEntryResponse;
import com.ldapportal.dto.ldap.MemberRequest;
import com.ldapportal.dto.ldap.UpdateEntryRequest;
import com.ldapportal.service.ApprovalWorkflowService;
import com.ldapportal.service.LdapOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GroupController.class)
class GroupControllerTest extends BaseControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean LdapOperationService ldapService;
    @MockitoBean ApprovalWorkflowService approvalService;

    static final UUID DIR_ID    = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final String BASE_URL = "/api/v1/directories/" + DIR_ID + "/groups";
    static final String ENTRY_DN = "cn=developers,ou=groups,dc=example,dc=com";

    LdapEntryResponse sampleEntry() {
        return new LdapEntryResponse(ENTRY_DN, Map.of(
                "cn",     List.of("developers"),
                "member", List.of("uid=alice,ou=people,dc=example,dc=com")));
    }

    // ── GET /search ───────────────────────────────────────────────────────────

    @Test
    void searchGroups_authenticated_returns200() throws Exception {
        given(ldapService.searchGroups(eq(DIR_ID), any(), isNull(), isNull(), anyInt(), any()))
                .willReturn(List.of(sampleEntry()));

        mockMvc.perform(get(BASE_URL).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dn").value(ENTRY_DN));
    }

    @Test
    void searchGroups_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /create ──────────────────────────────────────────────────────────

    @Test
    void createGroup_admin_returns201() throws Exception {
        CreateEntryRequest req = new CreateEntryRequest(ENTRY_DN,
                Map.of("cn", List.of("developers"), "objectClass", List.of("groupOfNames")));
        given(ldapService.createGroup(eq(DIR_ID), any(), any())).willReturn(sampleEntry());

        mockMvc.perform(post(BASE_URL)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dn").value(ENTRY_DN));
    }

    // ── GET /entry ────────────────────────────────────────────────────────────

    @Test
    void getGroup_byDn_returns200() throws Exception {
        given(ldapService.getGroup(eq(DIR_ID), any(), eq(ENTRY_DN), any()))
                .willReturn(sampleEntry());

        mockMvc.perform(get(BASE_URL + "/entry")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dn").value(ENTRY_DN))
                .andExpect(jsonPath("$.attributes.cn[0]").value("developers"));
    }

    // ── PUT /entry ────────────────────────────────────────────────────────────

    @Test
    void updateGroup_admin_returns200() throws Exception {
        UpdateEntryRequest req = new UpdateEntryRequest(
                List.of(new AttributeModification(AttributeModification.Operation.REPLACE,
                        "description", List.of("Dev team group"))));
        given(ldapService.updateGroup(eq(DIR_ID), any(), eq(ENTRY_DN), any()))
                .willReturn(sampleEntry());

        mockMvc.perform(put(BASE_URL + "/entry")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    // ── DELETE /entry ─────────────────────────────────────────────────────────

    @Test
    void deleteGroup_admin_returns204() throws Exception {
        willDoNothing().given(ldapService).deleteGroup(eq(DIR_ID), any(), eq(ENTRY_DN));

        mockMvc.perform(delete(BASE_URL + "/entry")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth())))
                .andExpect(status().isNoContent());
    }

    // ── GET /members ──────────────────────────────────────────────────────────

    @Test
    void getMembers_returns200() throws Exception {
        given(ldapService.getGroupMembers(eq(DIR_ID), any(), eq(ENTRY_DN), eq("member")))
                .willReturn(List.of("uid=alice,ou=people,dc=example,dc=com"));

        mockMvc.perform(get(BASE_URL + "/members")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── POST /members ─────────────────────────────────────────────────────────

    @Test
    void addMember_admin_returns204() throws Exception {
        MemberRequest req = new MemberRequest("member", "uid=bob,ou=people,dc=example,dc=com");
        willDoNothing().given(ldapService).addGroupMember(eq(DIR_ID), any(), eq(ENTRY_DN),
                eq("member"), eq("uid=bob,ou=people,dc=example,dc=com"));

        mockMvc.perform(post(BASE_URL + "/members")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    // ── DELETE /members ───────────────────────────────────────────────────────

    @Test
    void removeMember_admin_returns204() throws Exception {
        MemberRequest req = new MemberRequest("member", "uid=bob,ou=people,dc=example,dc=com");
        willDoNothing().given(ldapService).removeGroupMember(eq(DIR_ID), any(), eq(ENTRY_DN),
                eq("member"), eq("uid=bob,ou=people,dc=example,dc=com"));

        mockMvc.perform(delete(BASE_URL + "/members")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }
}
