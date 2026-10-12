// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.ApiRateLimiter;
import com.ldapportal.auth.RequiresFeature;
import com.ldapportal.controller.directory.BulkMembershipController;
import com.ldapportal.dto.csv.BulkMembershipPreviewResult;
import com.ldapportal.dto.csv.BulkMembershipPreviewRow;
import com.ldapportal.dto.csv.BulkMembershipResult;
import com.ldapportal.dto.csv.BulkMembershipRowResult;
import com.ldapportal.dto.ldap.MembershipChangeRequest.Op;
import com.ldapportal.entity.enums.FeatureKey;
import com.ldapportal.service.BulkMembershipService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BulkMembershipController.class)
class BulkMembershipControllerTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean BulkMembershipService service;
    @MockitoBean ApiRateLimiter rateLimiter;

    static final UUID DIR_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final String BASE_URL = "/api/v1/directories/" + DIR_ID + "/memberships/bulk";
    static final String BOB = "uid=bob,ou=users,dc=example,dc=com";
    static final String DEVS = "cn=devs,ou=groups,dc=example,dc=com";

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "memberships.csv", "text/csv", "user,groups\nbob,devs\n".getBytes());
    }

    @Test
    void preview_returnsClassifiedPairs() throws Exception {
        given(service.preview(eq(DIR_ID), any(), any())).willReturn(new BulkMembershipPreviewResult(1, 1, List.of(
                new BulkMembershipPreviewRow(1, "bob", BOB, "devs", DEVS, Op.ADD,
                        BulkMembershipPreviewRow.Disposition.WILL_ADD, null))));

        mockMvc.perform(multipart(BASE_URL + "/preview").file(file()).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionable").value(1))
                .andExpect(jsonPath("$.rows[0].userDn").value(BOB))
                .andExpect(jsonPath("$.rows[0].disposition").value("WILL_ADD"));
        verify(permissionService).requireFeature(any(), eq(DIR_ID), eq(FeatureKey.GROUP_MANAGE_MEMBERS));
    }

    @Test
    void apply_returnsPerPairResults() throws Exception {
        given(service.apply(eq(DIR_ID), any(), any())).willReturn(new BulkMembershipResult(1, 1, 0, 0, 0, List.of(
                new BulkMembershipRowResult(1, "bob", BOB, "devs", DEVS, Op.ADD,
                        BulkMembershipRowResult.Status.APPLIED, null, null))));

        mockMvc.perform(multipart(BASE_URL).file(file()).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.rows[0].status").value("APPLIED"));
    }

    @Test
    void withoutManageMembers_isForbiddenAndNeverResolves() throws Exception {
        doThrow(new AccessDeniedException("no"))
                .when(permissionService).requireFeature(any(), eq(DIR_ID), eq(FeatureKey.GROUP_MANAGE_MEMBERS));

        mockMvc.perform(multipart(BASE_URL + "/preview").file(file()).with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart(BASE_URL).file(file()).with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void badFile_mapsTo400() throws Exception {
        given(service.preview(eq(DIR_ID), any(), any()))
                .willThrow(new IllegalArgumentException("The file needs a 'user' column"));

        mockMvc.perform(multipart(BASE_URL + "/preview").file(file()).with(authentication(adminAuth())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The file needs a 'user' column"));
        verify(service, never()).apply(any(), any(), any());
    }

    @Test
    void bothEndpointsRequireBulkImport() {
        List<Method> endpoints = Arrays.stream(BulkMembershipController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("preview") || m.getName().equals("apply"))
                .toList();
        assertThat(endpoints).hasSize(2).allSatisfy(m ->
                assertThat(m.getAnnotation(RequiresFeature.class).value()).isEqualTo(FeatureKey.BULK_IMPORT));
    }
}
