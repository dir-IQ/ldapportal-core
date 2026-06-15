// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.controller.superadmin.AdminManagementController;
import com.ldapportal.dto.admin.AdminAccountRequest;
import com.ldapportal.dto.admin.AdminAccountResponse;
import com.ldapportal.dto.admin.AdminPermissionsResponse;
import com.ldapportal.dto.admin.CreateAdminWithPermissionsRequest;
import com.ldapportal.dto.admin.ProfileRoleRequest;
import com.ldapportal.dto.admin.ProfileRoleResponse;
import com.ldapportal.entity.enums.AccountRole;
import com.ldapportal.entity.enums.AccountType;
import com.ldapportal.entity.enums.BaseRole;
import com.ldapportal.service.AdminManagementService;
import com.ldapportal.service.EffectivePermissionsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminManagementController.class)
class AdminManagementControllerTest extends BaseControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean AdminManagementService service;
    @MockitoBean EffectivePermissionsService effectivePermissionsService;

    static final UUID ADMIN_ID   = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final UUID PROFILE_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    static final String BASE_URL = "/api/v1/superadmin/admins";

    AdminAccountResponse sampleResponse() {
        return new AdminAccountResponse(
                ADMIN_ID, 0L, "testadmin", "Test Admin", "testadmin@example.com",
                AccountRole.ADMIN, AccountType.LOCAL, null, true,
                null, Instant.now(), Instant.now(), true);
    }

    AdminAccountRequest sampleRequest() {
        return new AdminAccountRequest(
                "testadmin", "Test Admin", "testadmin@example.com",
                AccountRole.ADMIN, AccountType.LOCAL, "password123", null, true);
    }

    CreateAdminWithPermissionsRequest sampleUpsertRequest() {
        return new CreateAdminWithPermissionsRequest(sampleRequest(), List.of(), List.of());
    }

    @Test
    void listAdmins_superadmin_returns200() throws Exception {
        given(service.listAdmins()).willReturn(List.of(sampleResponse()));

        mockMvc.perform(get(BASE_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("testadmin"));
    }

    @Test
    void listAdmins_admin_returns403() throws Exception {
        mockMvc.perform(get(BASE_URL).with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void createAdmin_superadmin_returns201() throws Exception {
        given(service.createAdmin(any(), any())).willReturn(sampleResponse());

        mockMvc.perform(post(BASE_URL)
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("testadmin"));
    }

    // ── PUT /by-username/{username} (idempotent upsert) ─────────────────────────

    @Test
    void upsertByUsername_newAdmin_returns201() throws Exception {
        given(service.upsertByUsername(eq("testadmin"), any(), any(), any()))
                .willReturn(new AdminManagementService.AdminUpsertOutcome(sampleResponse(), true));

        mockMvc.perform(put(BASE_URL + "/by-username/testadmin")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleUpsertRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("testadmin"))
                .andExpect(jsonPath("$.passwordSet").value(true));
    }

    @Test
    void upsertByUsername_existingAdmin_returns200() throws Exception {
        given(service.upsertByUsername(eq("testadmin"), any(), any(), any()))
                .willReturn(new AdminManagementService.AdminUpsertOutcome(sampleResponse(), false));

        mockMvc.perform(put(BASE_URL + "/by-username/testadmin")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleUpsertRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testadmin"));
    }

    @Test
    void upsertByUsername_pathBodyMismatch_returns400() throws Exception {
        given(service.upsertByUsername(eq("other"), any(), any(), any()))
                .willThrow(new IllegalArgumentException(
                        "username in the path must match the account username in the body"));

        mockMvc.perform(put(BASE_URL + "/by-username/other")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleUpsertRequest())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upsertByUsername_admin_returns403() throws Exception {
        mockMvc.perform(put(BASE_URL + "/by-username/testadmin")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleUpsertRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAdmin_superadmin_returns200() throws Exception {
        given(service.getAdmin(eq(ADMIN_ID))).willReturn(sampleResponse());

        mockMvc.perform(get(BASE_URL + "/" + ADMIN_ID)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ADMIN_ID.toString()))
                .andExpect(jsonPath("$.username").value("testadmin"));
    }

    @Test
    void deleteAdmin_superadmin_returns204() throws Exception {
        willDoNothing().given(service).deleteAdmin(eq(ADMIN_ID));

        mockMvc.perform(delete(BASE_URL + "/" + ADMIN_ID)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isNoContent());
    }

    @Test
    void getPermissions_superadmin_returns200() throws Exception {
        given(service.getPermissions(eq(ADMIN_ID)))
                .willReturn(new AdminPermissionsResponse(List.of(), List.of()));

        mockMvc.perform(get(BASE_URL + "/" + ADMIN_ID + "/permissions")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileRoles").isArray())
                .andExpect(jsonPath("$.featurePermissions").isArray());
    }

    @Test
    void assignProfileRole_superadmin_returns200() throws Exception {
        ProfileRoleRequest req = new ProfileRoleRequest(PROFILE_ID, BaseRole.ADMIN);
        ProfileRoleResponse resp = new ProfileRoleResponse(
                UUID.randomUUID(), PROFILE_ID, "Test Profile", UUID.randomUUID(), BaseRole.ADMIN);
        given(service.assignProfileRole(eq(ADMIN_ID), any(), any())).willReturn(resp);

        mockMvc.perform(put(BASE_URL + "/" + ADMIN_ID + "/permissions/profile-roles")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value(PROFILE_ID.toString()))
                .andExpect(jsonPath("$.baseRole").value("ADMIN"));
    }
}
