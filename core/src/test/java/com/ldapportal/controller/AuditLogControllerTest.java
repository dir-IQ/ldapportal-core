// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.service.AuditQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @MockitoBean @SuppressWarnings("unused") AuditQueryService auditQueryService;
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
}
