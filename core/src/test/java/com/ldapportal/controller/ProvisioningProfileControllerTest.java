// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.controller.directory.ProvisioningProfileController;
import com.ldapportal.dto.profile.ProfileResponse;
import com.ldapportal.service.PasswordGeneratorService;
import com.ldapportal.service.ProvisioningProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authz tests for {@link ProvisioningProfileController#get}, which previously
 * skipped the per-profile permission check and let any ADMIN read any profile
 * by UUID even when they had no role on it. The controller now mirrors what
 * {@code list()} already did: superadmins bypass, plain admins must hold an
 * {@link com.ldapportal.entity.AdminProfileRole} on the requested profile.
 */
@WebMvcTest(ProvisioningProfileController.class)
class ProvisioningProfileControllerTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ProvisioningProfileService service;
    @MockitoBean PasswordGeneratorService passwordGenerator;

    static final UUID DIR_ID     = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final UUID PROFILE_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    static final String GET_URL  =
            "/api/v1/directories/" + DIR_ID + "/profiles/" + PROFILE_ID;

    private ProfileResponse stubResponse() {
        return new ProfileResponse(
                PROFILE_ID, DIR_ID, "dir-1", "p1", null,
                "ou=people,dc=example,dc=com",
                List.of("inetOrgPerson"), "uid",
                true, true, false,
                16, true, true, true, true, "!@#$%^&*", false,
                false, false,
                List.of(), List.of(), List.of(), List.of(),
                null, null);
    }

    @Test
    void getProfile_admin_withRole_returns200() throws Exception {
        given(service.get(DIR_ID, PROFILE_ID)).willReturn(stubResponse());

        mockMvc.perform(get(GET_URL).with(authentication(adminAuth())))
                .andExpect(status().isOk());

        verify(permissionService).requireProfileAccess(any(), eq(PROFILE_ID));
    }

    @Test
    void getProfile_admin_withoutRole_returns403() throws Exception {
        willThrow(new AccessDeniedException("No access to profile [" + PROFILE_ID + "]"))
                .given(permissionService).requireProfileAccess(any(), eq(PROFILE_ID));

        mockMvc.perform(get(GET_URL).with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void getProfile_superadmin_bypassesRoleCheck() throws Exception {
        given(service.get(DIR_ID, PROFILE_ID)).willReturn(stubResponse());

        mockMvc.perform(get(GET_URL).with(authentication(superadminAuth())))
                .andExpect(status().isOk());

        // The controller still calls requireProfileAccess; PermissionService
        // is responsible for short-circuiting superadmin, not the controller.
        verify(permissionService).requireProfileAccess(any(), eq(PROFILE_ID));
    }
}
