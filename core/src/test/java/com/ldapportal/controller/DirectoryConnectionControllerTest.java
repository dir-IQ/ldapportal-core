// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.controller.superadmin.DirectoryConnectionController;
import com.ldapportal.dto.directory.DirectoryConnectionRequest;
import com.ldapportal.dto.directory.DirectoryConnectionResponse;
import com.ldapportal.dto.directory.TestConnectionRequest;
import com.ldapportal.dto.directory.TestConnectionResult;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.PreconditionFailedException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.service.DirectoryConnectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// AuthPrincipal, PrincipalType, UsernamePasswordAuthenticationToken, SimpleGrantedAuthority
// are provided by BaseControllerTest helpers — no direct use here

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DirectoryConnectionController.class)
class DirectoryConnectionControllerTest extends BaseControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean DirectoryConnectionService directoryService;

    static final UUID DIR_ID    = UUID.fromString("20000000-0000-0000-0000-000000000002");

    static final String BASE_URL = "/api/v1/superadmin/directories";

    DirectoryConnectionResponse sampleResponse() {
        return new DirectoryConnectionResponse(
                DIR_ID,
                0L,                                 // version
                "corp-ldap",                        // slug
                com.ldapportal.entity.enums.DirectoryType.GENERIC, // directoryType
                "Corp LDAP",                        // displayName
                "ldap.example.com",                 // host
                389,                                // port
                SslMode.NONE,                       // sslMode
                false,                              // trustAllCerts
                "cn=admin,dc=example,dc=com",       // bindDn
                "dc=example,dc=com",                // baseDn
                500,                                // pagingSize
                1,                                  // poolMinSize
                10,                                 // poolMaxSize
                5,                                  // poolConnectTimeoutSeconds
                30,                                 // poolResponseTimeoutSeconds
                null,                               // enableDisableAttribute
                null,                               // enableDisableValueType
                null,                               // enableValue
                null,                               // disableValue
                null,                               // auditDataSourceId
                true,                               // enabled
                false,                              // selfServiceEnabled
                null,                               // selfServiceLoginAttribute
                List.of(),                          // userBaseDns
                List.of(),                          // groupBaseDns
                List.of(),                          // userObjectClasses
                List.of(),                          // groupObjectClasses
                null,                               // secondaryHost
                null,                               // secondaryPort
                null,                               // globalCatalogPort
                null,                               // tenantId
                null,                               // entraClientId
                null,                               // graphEndpoint
                null,                               // capabilities
                OffsetDateTime.now(),               // createdAt
                OffsetDateTime.now(),               // updatedAt
                true,                               // bindPasswordSet
                false);                             // entraClientSecretSet
    }

    DirectoryConnectionRequest validRequest() {
        return new DirectoryConnectionRequest(
                com.ldapportal.entity.enums.DirectoryType.GENERIC,
                "Corp LDAP", "ldap.example.com", 389, SslMode.NONE,
                false, null, "cn=admin,dc=example,dc=com", "secret",
                "dc=example,dc=com", 500, 1, 10, 5, 30,
                null, null, null, null, null, true,
                false, null, null, null, null,
                List.of(), List.of(),
                null, null,                         // user/group objectClasses
                null, null, null, null,
                null, null);
    }

    // ── GET list ──────────────────────────────────────────────────────────────

    @Test
    void listDirectories_superadmin_returns200() throws Exception {
        given(directoryService.listDirectories()).willReturn(List.of(sampleResponse()));

        mockMvc.perform(get(BASE_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Corp LDAP"))
                .andExpect(jsonPath("$[0].id").value(DIR_ID.toString()));
    }

    @Test
    void listDirectories_adminRole_returns403() throws Exception {
        mockMvc.perform(get(BASE_URL).with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void listDirectories_adminRole_forbidden_returnsProblemDetailNotLogin() throws Exception {
        // An authenticated-but-forbidden caller gets a clean 403 RFC-7807
        // ProblemDetail — not a 401 (which the SPA treats as a lost session and
        // bounces to the login screen). Guards the SecurityConfig accessDeniedHandler.
        mockMvc.perform(get(BASE_URL).with(authentication(adminAuth())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void listDirectories_anonymous_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isUnauthorized());
    }

    // ── POST create ───────────────────────────────────────────────────────────

    @Test
    void createDirectory_superadmin_returns201() throws Exception {
        given(directoryService.createDirectory(any())).willReturn(sampleResponse());

        mockMvc.perform(post(BASE_URL)
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(DIR_ID.toString()))
                .andExpect(jsonPath("$.displayName").value("Corp LDAP"));
    }

    @Test
    void createDirectory_blankDisplayName_returns400() throws Exception {
        DirectoryConnectionRequest bad = new DirectoryConnectionRequest(
                com.ldapportal.entity.enums.DirectoryType.GENERIC,
                "", "ldap.example.com", 389, SslMode.NONE,
                false, null, "cn=admin,dc=example,dc=com", "secret",
                "dc=example,dc=com", 500, 1, 10, 5, 30,
                null, null, null, null, null, true,
                false, null, null, null, null,
                List.of(), List.of(),
                null, null,                         // user/group objectClasses
                null, null, null, null,
                null, null);

        mockMvc.perform(post(BASE_URL)
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDirectory_portOutOfRange_returns400() throws Exception {
        DirectoryConnectionRequest bad = new DirectoryConnectionRequest(
                com.ldapportal.entity.enums.DirectoryType.GENERIC,
                "Corp LDAP", "ldap.example.com", 99999, SslMode.NONE,
                false, null, "cn=admin,dc=example,dc=com", "secret",
                "dc=example,dc=com", 500, 1, 10, 5, 30,
                null, null, null, null, null, true,
                false, null, null, null, null,
                List.of(), List.of(),
                null, null,                         // user/group objectClasses
                null, null, null, null,
                null, null);

        mockMvc.perform(post(BASE_URL)
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }

    // ── GET /{id} ─────────────────────────────────────────────────────────────

    @Test
    void getDirectory_superadmin_returns200() throws Exception {
        given(directoryService.getDirectory(DIR_ID)).willReturn(sampleResponse());

        mockMvc.perform(get(BASE_URL + "/" + DIR_ID).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("ldap.example.com"));
    }

    @Test
    void getDirectory_notFound_returns404() throws Exception {
        given(directoryService.getDirectory(DIR_ID))
                .willThrow(new ResourceNotFoundException("Directory not found"));

        mockMvc.perform(get(BASE_URL + "/" + DIR_ID).with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void getDirectory_carriesVersionETag() throws Exception {
        given(directoryService.getDirectory(DIR_ID)).willReturn(sampleResponse());

        mockMvc.perform(get(BASE_URL + "/" + DIR_ID).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.version").value(0));
    }

    // ── PUT /{id} (optimistic concurrency) ────────────────────────────────────

    @Test
    void updateDirectory_staleIfMatch_returns412() throws Exception {
        // The controller must parse If-Match "5" → 5L and hand it to the
        // service, which rejects the stale precondition.
        given(directoryService.updateDirectory(eq(DIR_ID), any(), eq(5L)))
                .willThrow(new PreconditionFailedException("stale"));

        mockMvc.perform(put(BASE_URL + "/" + DIR_ID)
                        .with(authentication(superadminAuth()))
                        .header("If-Match", "\"5\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void updateDirectory_matchingIfMatch_returns200WithETag() throws Exception {
        given(directoryService.updateDirectory(eq(DIR_ID), any(), eq(0L)))
                .willReturn(sampleResponse());

        mockMvc.perform(put(BASE_URL + "/" + DIR_ID)
                        .with(authentication(superadminAuth()))
                        .header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""));
    }

    // ── PUT /{id} ─────────────────────────────────────────────────────────────

    @Test
    void updateDirectory_superadmin_returns200() throws Exception {
        given(directoryService.updateDirectory(eq(DIR_ID), any(), any()))
                .willReturn(sampleResponse());

        mockMvc.perform(put(BASE_URL + "/" + DIR_ID)
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DIR_ID.toString()));
    }

    // ── PUT /by-slug/{slug} (idempotent upsert) ─────────────────────────────────

    @Test
    void upsertBySlug_newDirectory_returns201() throws Exception {
        given(directoryService.upsertBySlug(eq("corp-ldap"), any(), any()))
                .willReturn(new DirectoryConnectionService.UpsertOutcome(sampleResponse(), true));

        mockMvc.perform(put(BASE_URL + "/by-slug/corp-ldap")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("corp-ldap"))
                .andExpect(jsonPath("$.bindPasswordSet").value(true))
                .andExpect(jsonPath("$.entraClientSecretSet").value(false));
    }

    @Test
    void upsertBySlug_existingDirectory_returns200() throws Exception {
        given(directoryService.upsertBySlug(eq("corp-ldap"), any(), any()))
                .willReturn(new DirectoryConnectionService.UpsertOutcome(sampleResponse(), false));

        mockMvc.perform(put(BASE_URL + "/by-slug/corp-ldap")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("corp-ldap"));
    }

    @Test
    void upsertBySlug_invalidSlug_returns400() throws Exception {
        given(directoryService.upsertBySlug(eq("Bad_Slug"), any(), any()))
                .willThrow(new IllegalArgumentException("slug must be lowercase alphanumeric segments"));

        mockMvc.perform(put(BASE_URL + "/by-slug/Bad_Slug")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void upsertBySlug_adminRole_returns403() throws Exception {
        mockMvc.perform(put(BASE_URL + "/by-slug/corp-ldap")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isForbidden());
    }

    // ── DELETE /{id} ──────────────────────────────────────────────────────────

    @Test
    void deleteDirectory_superadmin_returns204() throws Exception {
        willDoNothing().given(directoryService).deleteDirectory(DIR_ID);

        mockMvc.perform(delete(BASE_URL + "/" + DIR_ID).with(authentication(superadminAuth())))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteDirectory_notFound_returns404() throws Exception {
        willThrow(new ResourceNotFoundException("Directory not found"))
                .given(directoryService).deleteDirectory(DIR_ID);

        mockMvc.perform(delete(BASE_URL + "/" + DIR_ID).with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    // ── POST /{id}/evict-pool ─────────────────────────────────────────────────

    @Test
    void evictPool_superadmin_returns204() throws Exception {
        willDoNothing().given(directoryService).evictPool(DIR_ID);

        mockMvc.perform(post(BASE_URL + "/" + DIR_ID + "/evict-pool")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isNoContent());
    }

    // ── GET /{id}/status ──────────────────────────────────────────────────────

    @Test
    void status_reachable_returns200WithSuccessTrue() throws Exception {
        given(directoryService.checkConnection(DIR_ID))
                .willReturn(new TestConnectionResult(true, "Reachable", 12L));

        mockMvc.perform(get(BASE_URL + "/" + DIR_ID + "/status")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void status_unreachable_returns200WithSuccessFalse() throws Exception {
        given(directoryService.checkConnection(DIR_ID))
                .willReturn(new TestConnectionResult(false, "UnknownHostException", 5L));

        mockMvc.perform(get(BASE_URL + "/" + DIR_ID + "/status")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("UnknownHostException"));
    }

    @Test
    void status_adminRole_returns403() throws Exception {
        mockMvc.perform(get(BASE_URL + "/" + DIR_ID + "/status")
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    // ── POST /test ────────────────────────────────────────────────────────────

    @Test
    void testConnection_success_returns200() throws Exception {
        TestConnectionRequest req = new TestConnectionRequest(
                "ldap.example.com", 389, SslMode.NONE, false, null,
                "cn=admin,dc=example,dc=com", "secret");
        TestConnectionResult result = new TestConnectionResult(true, "Connected successfully", 42L);
        given(directoryService.testConnection(any())).willReturn(result);

        mockMvc.perform(post(BASE_URL + "/test")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void testConnection_failure_returns200WithSuccessFalse() throws Exception {
        TestConnectionRequest req = new TestConnectionRequest(
                "unreachable.example.com", 389, SslMode.NONE, false, null,
                "cn=admin,dc=example,dc=com", "secret");
        TestConnectionResult result = new TestConnectionResult(false, "Connection refused", 0L);
        given(directoryService.testConnection(any())).willReturn(result);

        mockMvc.perform(post(BASE_URL + "/test")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }
}
