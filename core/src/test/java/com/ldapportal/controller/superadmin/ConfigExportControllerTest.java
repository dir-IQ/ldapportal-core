// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.service.ConfigExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ConfigExportController.class)
class ConfigExportControllerTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ConfigExportService configExportService;

    @Test
    void export_asSuperadmin_returnsYamlAttachment() throws Exception {
        when(configExportService.exportYaml()).thenReturn("directories: []\n");

        mockMvc.perform(get("/api/v1/superadmin/config/export").with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/yaml"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"bootstrap-config.yml\""))
                .andExpect(content().string("directories: []\n"));
    }

    @Test
    void export_asAdmin_isForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/superadmin/config/export").with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void export_unauthenticated_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/superadmin/config/export"))
                .andExpect(status().isUnauthorized());
    }
}
