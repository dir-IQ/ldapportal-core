// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.controller.directory.LifecyclePlaybookController;
import com.ldapportal.dto.playbook.PlaybookResponse;
import com.ldapportal.service.ApplicationSettingsService;
import com.ldapportal.service.LifecyclePlaybookService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the global {@code playbooksEnabled} switch on the playbook API: every
 * endpoint must refuse with 403 while the feature is off in Application
 * Settings, and behave normally once it is on.
 */
@WebMvcTest(LifecyclePlaybookController.class)
class LifecyclePlaybookControllerTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean LifecyclePlaybookService playbookService;
    @MockitoBean ApplicationSettingsService settingsService;

    static final UUID DIR_ID      = UUID.fromString("40000000-0000-0000-0000-000000000004");
    static final UUID PLAYBOOK_ID = UUID.fromString("40000000-0000-0000-0000-000000000005");
    static final String BASE_URL  = "/api/v1/directories/" + DIR_ID + "/playbooks";

    PlaybookResponse samplePlaybook() {
        return new PlaybookResponse(PLAYBOOK_ID, DIR_ID, "Offboard", null, "OFFBOARDING",
                null, null, false, true, List.of(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    // ── Feature enabled ──────────────────────────────────────────────────────

    @Test
    void list_playbooksEnabled_returns200() throws Exception {
        given(settingsService.isPlaybooksEnabled()).willReturn(true);
        given(playbookService.list(DIR_ID)).willReturn(List.of(samplePlaybook()));

        mockMvc.perform(get(BASE_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Offboard"));
    }

    // ── Feature disabled ─────────────────────────────────────────────────────

    @Test
    void list_playbooksDisabled_returns403_andNeverHitsService() throws Exception {
        given(settingsService.isPlaybooksEnabled()).willReturn(false);

        mockMvc.perform(get(BASE_URL).with(authentication(superadminAuth())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(
                        "Lifecycle playbooks are disabled in application settings"));

        verifyNoInteractions(playbookService);
    }

    @Test
    void listEnabled_playbooksDisabled_returns403() throws Exception {
        given(settingsService.isPlaybooksEnabled()).willReturn(false);

        mockMvc.perform(get(BASE_URL + "/enabled").with(authentication(superadminAuth())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(playbookService);
    }

    @Test
    void create_playbooksDisabled_returns403() throws Exception {
        given(settingsService.isPlaybooksEnabled()).willReturn(false);

        mockMvc.perform(post(BASE_URL)
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Offboard\",\"type\":\"OFFBOARDING\",\"steps\":[]}"))
                .andExpect(status().isForbidden());

        verify(playbookService, never()).create(any(), any());
    }

    @Test
    void delete_playbooksDisabled_returns403() throws Exception {
        given(settingsService.isPlaybooksEnabled()).willReturn(false);

        mockMvc.perform(delete(BASE_URL + "/" + PLAYBOOK_ID).with(authentication(superadminAuth())))
                .andExpect(status().isForbidden());

        verify(playbookService, never()).delete(any(), any());
    }

    @Test
    void execute_playbooksDisabled_returns403() throws Exception {
        given(settingsService.isPlaybooksEnabled()).willReturn(false);

        mockMvc.perform(post(BASE_URL + "/" + PLAYBOOK_ID + "/execute")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetDns\":[\"uid=alice,ou=people,dc=example,dc=com\"]}"))
                .andExpect(status().isForbidden());

        verify(playbookService, never()).execute(any(), any(), any(), any());
    }

    @Test
    void rollback_playbooksDisabled_returns403() throws Exception {
        given(settingsService.isPlaybooksEnabled()).willReturn(false);

        mockMvc.perform(post(BASE_URL + "/executions/" + UUID.randomUUID() + "/rollback")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isForbidden());

        verify(playbookService, never()).rollback(any(), any());
    }
}
