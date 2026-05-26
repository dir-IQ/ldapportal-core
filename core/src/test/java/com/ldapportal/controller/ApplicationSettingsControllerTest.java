// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.dto.settings.ApplicationSettingsDto;
import com.ldapportal.dto.settings.BrandingDto;
import com.ldapportal.dto.settings.UpdateApplicationSettingsRequest;
import com.ldapportal.service.ApplicationSettingsService;
import com.ldapportal.core.siem.service.SiemExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApplicationSettingsController.class)
class ApplicationSettingsControllerTest extends BaseControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ApplicationSettingsService settingsService;
    @MockitoBean SiemExportService          siemExportService;

    static final String BASE_URL = "/api/v1/settings";

    ApplicationSettingsDto sampleSettings() {
        return new ApplicationSettingsDto(
                UUID.randomUUID(),
                "LDAPPortal", null, null, null,
                true,    // directorySearchInlineEditEnabled
                30,
                null, null, null, null, false, false,
                null, null, null, false, null, 24,
                null,
                null, null, null, false, null, null, false, null, null,
                null, null, false, null, null, null,
                // SIEM
                false, null, null, null, null, false, null, false,
                // WebSEAL
                null, "iv-user", "iv-groups", "/pkmslogout",
                // Auth-toggle UI visibility
                false, true,
                // Setup wizard
                false,
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    BrandingDto sampleBranding() {
        return new BrandingDto("LDAPPortal", null, null, null, null);
    }

    UpdateApplicationSettingsRequest sampleUpdateRequest() {
        return new UpdateApplicationSettingsRequest(
                "LDAPPortal", null, null, null,
                null,    // directorySearchInlineEditEnabled (Boolean wrapper, null preserves)
                30,
                null, null, null, null, null, false,
                null, null, null, null, null, 1,
                null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                // SIEM
                null, null, null, null, null, null, null, null,
                // WebSEAL
                null, null, null, null,
                // Setup wizard
                null);
    }

    // ── GET /api/v1/settings ─────────────────────────────────────────────────

    @Test
    void getSettings_authenticated_returns200() throws Exception {
        given(settingsService.get()).willReturn(sampleSettings());

        mockMvc.perform(get(BASE_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("LDAPPortal"));
    }

    @Test
    void getSettings_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getSettings_returnsSiemFields() throws Exception {
        ApplicationSettingsDto withSiem = new ApplicationSettingsDto(
                UUID.randomUUID(),
                "LDAPPortal", null, null, null,
                true, 30,
                null, null, null, null, false, false,
                null, null, null, false, null, 24,
                null,
                null, null, null, false, null, null, false, null, null,
                null, null, false, null, null, null,
                // SIEM
                true, com.ldapportal.entity.enums.SiemProtocol.SYSLOG_UDP,
                "siem.corp.com", 514, com.ldapportal.entity.enums.SiemFormat.CEF,
                true, null, false,
                // WebSEAL
                null, "iv-user", "iv-groups", "/pkmslogout",
                // Auth-toggle UI visibility
                false, true,
                // Setup wizard
                false,
                OffsetDateTime.now(), OffsetDateTime.now());

        given(settingsService.get()).willReturn(withSiem);

        mockMvc.perform(get(BASE_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.siemEnabled").value(true))
                .andExpect(jsonPath("$.siemProtocol").value("SYSLOG_UDP"))
                .andExpect(jsonPath("$.siemHost").value("siem.corp.com"))
                .andExpect(jsonPath("$.siemFormat").value("CEF"))
                .andExpect(jsonPath("$.siemAuthTokenConfigured").value(true));
    }

    // ── GET /api/v1/settings/branding ────────────────────────────────────────

    @Test
    void getBranding_unauthenticated_returns200() throws Exception {
        given(settingsService.getBranding()).willReturn(sampleBranding());

        mockMvc.perform(get(BASE_URL + "/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("LDAPPortal"));
    }

    // ── PUT /api/v1/settings ─────────────────────────────────────────────────

    @Test
    void upsertSettings_authenticated_returns200() throws Exception {
        given(settingsService.upsert(any())).willReturn(sampleSettings());

        mockMvc.perform(put(BASE_URL)
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sampleUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("LDAPPortal"));
    }

    // ── POST /api/v1/settings/siem/test ──────────────────────────────────────

    @Test
    void testSiem_authenticated_returns200() throws Exception {
        given(siemExportService.testConnectivity()).willReturn("TCP: Connected");
        given(siemExportService.sendTestEvent()).willReturn("Test event sent successfully.");

        mockMvc.perform(post(BASE_URL + "/siem/test")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectivity").value("TCP: Connected"))
                .andExpect(jsonPath("$.delivery").value("Test event sent successfully."));
    }

    @Test
    void testSiem_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(BASE_URL + "/siem/test"))
                .andExpect(status().isUnauthorized());
    }
}
