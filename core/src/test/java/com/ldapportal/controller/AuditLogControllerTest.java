// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.service.AuditQueryService;
import com.ldapportal.dto.audit.AuditQueryCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.Writer;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the audit-action filter catalogue ({@code GET /api/v1/audit/actions})
 * is edition-filtered through the entitlement gate — the endpoint that replaced
 * the client's hand-maintained non-community exclude list. The exhaustive
 * per-constant contract lives in {@code EditionLeakGuardTest}; this pins the
 * wire behaviour and authz.
 */
@WebMvcTest(AuditLogController.class)
class AuditLogControllerTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AuditQueryService auditQueryService;
    @MockitoBean com.ldapportal.core.entitlement.EntitlementService entitlementService;

    private static final String ACTIONS_URL = "/api/v1/audit/actions";

    @Test
    void actions_communityEdition_excludesNonCommunityActions() throws Exception {
        given(entitlementService.exposed(any())).willCallRealMethod();
        given(entitlementService.exposes(any())).willCallRealMethod();
        given(entitlementService.has(any())).willReturn(false);

        mockMvc.perform(get(ACTIONS_URL).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("USER_CREATE")))
                .andExpect(jsonPath("$", not(hasItem("CAMPAIGN_CREATED"))))
                .andExpect(jsonPath("$", not(hasItem("REVIEW_CONFIRMED"))))
                .andExpect(jsonPath("$", not(hasItem("SOD_POLICY_CREATED"))))
                .andExpect(jsonPath("$", not(hasItem("HR_SYNC_STARTED"))))
                .andExpect(jsonPath("$", not(hasItem("AUDITOR_LINK_CREATED"))));
    }

    @Test
    void actions_fullyEntitled_includesGovernanceAndHrActions() throws Exception {
        given(entitlementService.exposed(any())).willCallRealMethod();
        given(entitlementService.exposes(any())).willCallRealMethod();
        given(entitlementService.has(any())).willReturn(true);

        mockMvc.perform(get(ACTIONS_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasItem("USER_CREATE")))
                .andExpect(jsonPath("$", hasItem("SOD_POLICY_CREATED")))
                .andExpect(jsonPath("$", hasItem("HR_SYNC_STARTED")));
    }

    private static final String EXPORT_URL = "/api/v1/audit/export.csv";

    @Test
    void export_superadmin_returnsCsvAndCounts() throws Exception {
        given(auditQueryService.exportCsv(any(), isNull(), any())).willAnswer(inv -> {
            AuditQueryCriteria c = inv.getArgument(0);
            assertThat(c.source()).isEqualTo("directory-sync");
            Writer w = inv.getArgument(2);
            w.write("\"occurredAt\"\r\n");
            w.flush();
            return new AuditQueryService.ExportResult(0, 0, false);
        });

        mockMvc.perform(get(EXPORT_URL).param("source", "directory-sync")
                        .param("from", "2026-10-27T00:00:00Z").param("to", "2026-10-28T00:00:00Z")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"audit-log.csv\""))
                .andExpect(header().string("X-Audit-Export-Matched", "0"))
                .andExpect(header().string("X-Audit-Export-Truncated", "false"))
                .andExpect(content().string("\"occurredAt\"\r\n"));
    }

    @Test
    void export_admin_isScopedToTheirDirectories() throws Exception {
        Set<UUID> dirs = Set.of(UUID.randomUUID());
        given(permissionService.getAuthorizedDirectoryIds(any())).willReturn(dirs);
        given(auditQueryService.exportCsv(any(), org.mockito.ArgumentMatchers.eq(dirs), any()))
                .willReturn(new AuditQueryService.ExportResult(0, 60_000, true));

        mockMvc.perform(get(EXPORT_URL).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Audit-Export-Truncated", "true"));
    }

    @Test
    void export_admin_otherDirectory_returns403() throws Exception {
        given(permissionService.getAuthorizedDirectoryIds(any())).willReturn(Set.of(UUID.randomUUID()));

        mockMvc.perform(get(EXPORT_URL).param("directoryId", UUID.randomUUID().toString())
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    // ── GET /api/v1/audit: access rules (shared with the export) ──────────────

    private static final String LOG_URL = "/api/v1/audit";

    @Test
    void log_superadmin_queriesAnyDirectory() throws Exception {
        given(auditQueryService.query(any(), anyInt(), anyInt())).willReturn(Page.empty());

        mockMvc.perform(get(LOG_URL).param("source", "directory-sync").with(authentication(superadminAuth())))
                .andExpect(status().isOk());

        verify(auditQueryService).query(any(), anyInt(), anyInt());
    }

    @Test
    void log_adminWithoutDirectory_isScopedToTheirDirectories() throws Exception {
        Set<UUID> dirs = Set.of(UUID.randomUUID());
        given(permissionService.getAuthorizedDirectoryIds(any())).willReturn(dirs);
        given(auditQueryService.queryForDirectories(any(), any(), anyInt(), anyInt())).willReturn(Page.empty());

        mockMvc.perform(get(LOG_URL).with(authentication(adminAuth())))
                .andExpect(status().isOk());

        verify(auditQueryService).queryForDirectories(org.mockito.ArgumentMatchers.eq(dirs), any(), anyInt(),
                anyInt());
    }

    @Test
    void log_adminOtherDirectory_returns403() throws Exception {
        given(permissionService.getAuthorizedDirectoryIds(any())).willReturn(Set.of(UUID.randomUUID()));

        mockMvc.perform(get(LOG_URL).param("directoryId", UUID.randomUUID().toString())
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void log_adminTargetDnOutsideTheirScope_returns403() throws Exception {
        given(permissionService.getAuthorizedDirectoryIds(any())).willReturn(Set.of(UUID.randomUUID()));
        given(permissionService.isDnWithinScope(any(), any(), any())).willReturn(false);

        mockMvc.perform(get(LOG_URL).param("targetDn", "uid=x,ou=other,dc=corp")
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }
}
