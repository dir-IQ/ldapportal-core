// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.reports;

import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.core.entitlement.EntitlementService;
import com.ldapportal.core.reports.schedule.ReportDeliveryMethod;
import com.ldapportal.core.reports.schedule.ReportOutputFormat;
import com.ldapportal.core.reports.schedule.ScheduledReportJobScheduler;
import com.ldapportal.core.reports.schedule.ScheduledReportJobService;
import com.ldapportal.core.reports.schedule.ScheduledReportType;
import com.ldapportal.entity.ScheduledReportJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReportJobController.class)
class ReportJobControllerTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ScheduledReportJobService jobService;
    @MockitoBean ScheduledReportJobScheduler scheduler;
    @MockitoBean EntitlementService entitlementService;

    private static final UUID DIR = UUID.randomUUID();
    private static final String BASE = "/api/v1/directories/" + DIR + "/report-jobs";

    private ScheduledReportJob job(String name) {
        ScheduledReportJob j = new ScheduledReportJob();
        j.setId(UUID.randomUUID());
        j.setDirectoryId(DIR);
        j.setName(name);
        j.setReportType("DISABLED_ACCOUNTS");
        j.setCronExpression("0 0 8 * * MON");
        j.setOutputFormat(ReportOutputFormat.CSV);
        j.setDeliveryMethod(ReportDeliveryMethod.EMAIL);
        j.setDeliveryRecipients("ops@example.com");
        j.setEnabled(true);
        return j;
    }

    private static final String CREATE_BODY = """
            {"name":"Weekly","reportType":"DISABLED_ACCOUNTS","reportParams":{"lookbackDays":30},
             "cronExpression":"0 8 * * MON","outputFormat":"CSV","deliveryMethod":"EMAIL",
             "recipientEmail":"ops@example.com","timezone":"America/New_York","enabled":true}""";

    @Test
    void create_returns201_andMapsBody() throws Exception {
        given(jobService.create(eq(DIR), any(), any())).willReturn(job("Weekly"));

        mockMvc.perform(post(BASE).with(authentication(adminAuth())).with(csrf())
                        .contentType("application/json").content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Weekly"))
                .andExpect(jsonPath("$.reportType").value("DISABLED_ACCOUNTS"))
                .andExpect(jsonPath("$.outputFormat").value("CSV"))
                .andExpect(jsonPath("$.recipientEmail").value("ops@example.com"));
    }

    @Test
    void create_invalidBody_returns400() throws Exception {
        String noName = """
                {"reportType":"DISABLED_ACCOUNTS","cronExpression":"0 8 * * MON",
                 "outputFormat":"CSV","deliveryMethod":"EMAIL","recipientEmail":"a@b.com","enabled":true}""";
        mockMvc.perform(post(BASE).with(authentication(adminAuth())).with(csrf())
                        .contentType("application/json").content(noName))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_returnsPagedJobs() throws Exception {
        given(jobService.list(eq(DIR), any()))
                .willReturn(new PageImpl<>(List.of(job("A"), job("B"))));

        mockMvc.perform(get(BASE + "?size=20").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("A"))
                .andExpect(jsonPath("$.content[1].name").value("B"));
    }

    @Test
    void get_returnsJob() throws Exception {
        ScheduledReportJob j = job("One");
        given(jobService.get(DIR, j.getId())).willReturn(j);

        mockMvc.perform(get(BASE + "/" + j.getId()).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("One"));
    }

    @Test
    void delete_returns204() throws Exception {
        UUID jobId = UUID.randomUUID();
        mockMvc.perform(delete(BASE + "/" + jobId).with(authentication(adminAuth())).with(csrf()))
                .andExpect(status().isNoContent());
        verify(jobService).delete(DIR, jobId);
    }

    @Test
    void runNow_returns202_andTriggersAsyncRun() throws Exception {
        ScheduledReportJob j = job("Now");
        given(jobService.get(DIR, j.getId())).willReturn(j);

        mockMvc.perform(post(BASE + "/" + j.getId() + "/run-now")
                        .with(authentication(adminAuth())).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("started"));
        verify(scheduler).runNowAsync(j);
    }

    @Test
    void catalogue_returnsExposedTypesFormatsDeliveries() throws Exception {
        given(jobService.exposedReportTypes())
                .willReturn(List.of(ScheduledReportType.core("DISABLED_ACCOUNTS", "Disabled Accounts")));
        given(entitlementService.exposed(ReportOutputFormat.class))
                .willReturn(List.of(ReportOutputFormat.CSV));
        given(entitlementService.exposed(ReportDeliveryMethod.class))
                .willReturn(List.of(ReportDeliveryMethod.EMAIL));

        mockMvc.perform(get("/api/v1/directories/" + DIR + "/report-types")
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.types[0].id").value("DISABLED_ACCOUNTS"))
                .andExpect(jsonPath("$.types[0].label").value("Disabled Accounts"))
                .andExpect(jsonPath("$.formats[0]").value("CSV"))
                .andExpect(jsonPath("$.deliveries[0]").value("EMAIL"));
    }
}
