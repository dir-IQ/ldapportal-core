// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ldapportal.service.UserPreferencesService;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PreferencesController.class)
class PreferencesControllerTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean UserPreferencesService service;

    @Test
    void get_returnsDocument() throws Exception {
        given(service.get(any(UUID.class)))
                .willReturn(Map.of("appearance", Map.of("theme", "dark")));

        mockMvc.perform(get("/api/v1/me/preferences").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appearance.theme").value("dark"));
    }

    @Test
    void get_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/me/preferences"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patch_appliesMergeAndReturnsDocument() throws Exception {
        given(service.applyMergePatch(any(UUID.class), any(JsonNode.class)))
                .willReturn(Map.of("appearance", Map.of("theme", "light")));

        mockMvc.perform(patch("/api/v1/me/preferences")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appearance\":{\"theme\":\"light\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appearance.theme").value("light"));

        verify(service).applyMergePatch(any(UUID.class), any(JsonNode.class));
    }

    @Test
    void patch_unknownNamespace_returns400() throws Exception {
        given(service.applyMergePatch(any(UUID.class), any(JsonNode.class)))
                .willThrow(new IllegalArgumentException("Unknown preferences namespace: bogus"));

        mockMvc.perform(patch("/api/v1/me/preferences")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bogus\":{}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void put_replacesNamespace() throws Exception {
        given(service.replaceNamespace(any(UUID.class), eq("tables"), any(JsonNode.class)))
                .willReturn(Map.of("tables", Map.of("audit", Map.of("pageSize", 50))));

        mockMvc.perform(put("/api/v1/me/preferences/tables")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audit\":{\"pageSize\":50}}"))
                .andExpect(status().isOk());

        verify(service).replaceNamespace(any(UUID.class), eq("tables"), any(JsonNode.class));
    }

    @Test
    void delete_clearsNamespace() throws Exception {
        given(service.clearNamespace(any(UUID.class), eq("sidebar")))
                .willReturn(Map.of());

        mockMvc.perform(delete("/api/v1/me/preferences/sidebar")
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk());

        verify(service).clearNamespace(any(UUID.class), eq("sidebar"));
    }
}
