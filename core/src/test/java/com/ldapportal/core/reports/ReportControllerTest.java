// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import com.ldapportal.auth.ApiRateLimiter;
import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.repository.DirectoryConnectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportController.class)
class ReportControllerTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean OperationalReportService reportService;
    @MockitoBean DirectoryConnectionRepository dirRepo;
    @MockitoBean ApiRateLimiter rateLimiter;

    private static final UUID DIR = UUID.randomUUID();
    private static final String BASE = "/api/v1/directories/" + DIR + "/reports";

    private void stubReport() {
        given(dirRepo.findById(DIR)).willReturn(Optional.of(new DirectoryConnection()));
        Map<String, String> row = new LinkedHashMap<>();
        row.put("dn", "uid=a");
        row.put("cn", "A");
        row.put("mail", "a@x");
        row.put("uid", "a");
        given(reportService.run(any(), eq("USERS_IN_BRANCH"), any(), eq(DIR), any()))
                .willReturn(new ReportData(List.of("dn", "cn", "mail", "uid"), List.of(row)));
    }

    @Test
    void csvExport_carriesOnlyTheRequestedColumns_inTheirOrder() throws Exception {
        stubReport();

        mockMvc.perform(post(BASE + "/run").with(authentication(adminAuth())).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"reportType":"USERS_IN_BRANCH","reportParams":{"branchDn":"ou=p"},
                                 "columns":["uid","mail"]}"""))
                .andExpect(status().isOk())
                .andExpect(content().string("\"dn\",\"uid\",\"mail\"\r\n\"uid=a\",\"a\",\"a@x\"\r\n"));
    }

    @Test
    void csvExport_withoutColumns_carriesEveryColumn() throws Exception {
        stubReport();

        mockMvc.perform(post(BASE + "/run").with(authentication(adminAuth())).with(csrf())
                        .contentType("application/json")
                        .content("{\"reportType\":\"USERS_IN_BRANCH\",\"reportParams\":{\"branchDn\":\"ou=p\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "\"dn\",\"cn\",\"mail\",\"uid\"\r\n\"uid=a\",\"A\",\"a@x\",\"a\"\r\n"));
    }

    @Test
    void csvExport_isNamedLikeTheScheduledCsvOfTheSameReport() throws Exception {
        stubReport();

        // CsvReportRenderer names a scheduled "Users In Branch" CSV users-in-branch.csv.
        mockMvc.perform(post(BASE + "/run").with(authentication(adminAuth())).with(csrf())
                        .contentType("application/json")
                        .content("{\"reportType\":\"USERS_IN_BRANCH\",\"reportParams\":{\"branchDn\":\"ou=p\"}}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"users-in-branch.csv\""));
    }

    @Test
    void runData_ignoresColumns_soTheTableCanOfferEveryOne() throws Exception {
        stubReport();

        mockMvc.perform(post(BASE + "/run-data").with(authentication(adminAuth())).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"reportType":"USERS_IN_BRANCH","reportParams":{"branchDn":"ou=p"},
                                 "columns":["uid"]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns.length()").value(4));
    }
}
