// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.controller.directory.UserController;
import com.ldapportal.dto.ldap.AttributeModification;
import com.ldapportal.dto.ldap.CreateEntryRequest;
import com.ldapportal.dto.ldap.LdapEntryResponse;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeRequest.Change;
import com.ldapportal.dto.ldap.MembershipChangeRequest.Op;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.dto.ldap.MoveUserRequest;
import com.ldapportal.dto.ldap.UpdateEntryRequest;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.service.ApprovalWorkflowService;
import com.ldapportal.service.LdapOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest extends BaseControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean LdapOperationService ldapService;
    @MockitoBean ApprovalWorkflowService approvalService;
    @MockitoBean com.ldapportal.service.ProvisioningProfileService provisioningProfileService;
    @MockitoBean com.ldapportal.service.PasswordPolicyService passwordPolicyService;
    @MockitoBean com.ldapportal.service.ApprovalNotificationService approvalNotificationService;

    static final UUID DIR_ID    = UUID.fromString("20000000-0000-0000-0000-000000000002");
    static final String BASE_URL = "/api/v1/directories/" + DIR_ID + "/users";
    static final String ENTRY_DN = "uid=alice,ou=people,dc=example,dc=com";

    LdapEntryResponse sampleEntry() {
        return new LdapEntryResponse(ENTRY_DN, Map.of(
                "cn",   List.of("Alice"),
                "mail", List.of("alice@example.com")));
    }

    // ── GET /search ───────────────────────────────────────────────────────────

    @Test
    void searchUsers_authenticated_returns200() throws Exception {
        given(ldapService.searchUsers(eq(DIR_ID), any(), isNull(), isNull(), anyInt(), any()))
                .willReturn(List.of(sampleEntry()));

        mockMvc.perform(get(BASE_URL).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dn").value(ENTRY_DN));
    }

    @Test
    void searchUsers_withFilter_returns200() throws Exception {
        given(ldapService.searchUsers(eq(DIR_ID), any(), anyString(), isNull(), anyInt(), any()))
                .willReturn(List.of(sampleEntry()));

        mockMvc.perform(get(BASE_URL)
                        .param("filter", "(cn=alice)")
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk());
    }

    @Test
    void searchUsers_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isUnauthorized());
    }

    // ── POST /create ──────────────────────────────────────────────────────────

    @Test
    void createUser_admin_returns201() throws Exception {
        CreateEntryRequest req = new CreateEntryRequest(ENTRY_DN,
                Map.of("cn", List.of("Alice"), "sn", List.of("Smith")));
        // Profile-aware overload — fourth arg is the optional profileId
        // (null when no profile matched the target DN).
        given(ldapService.createUser(eq(DIR_ID), any(), any(), any()))
                .willReturn(sampleEntry());

        mockMvc.perform(post(BASE_URL)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entry.dn").value(ENTRY_DN))
                // No profile matched → no group assignments attempted.
                .andExpect(jsonPath("$.groupsAdded").value(0))
                .andExpect(jsonPath("$.groupWarnings").isEmpty());
    }

    @Test
    void createUser_profileGroupFailures_returnedAsWarnings() throws Exception {
        // Per-group assignment failures must not fail the create (the entry
        // exists) but must ride back on the 201 body — previously they were
        // swallowed into a server-side log line and the UI reported full
        // success. Pin the partial-success contract the UI keys off.
        UUID profileId = UUID.fromString("40000000-0000-0000-0000-000000000004");
        com.ldapportal.entity.ProvisioningProfile profile =
                org.mockito.Mockito.mock(com.ldapportal.entity.ProvisioningProfile.class);
        given(profile.getId()).willReturn(profileId);
        given(approvalService.findProfileForDn(eq(DIR_ID), anyString()))
                .willReturn(java.util.Optional.of(profile));
        given(ldapService.createUser(eq(DIR_ID), any(), any(), any()))
                .willReturn(sampleEntry());
        given(provisioningProfileService.applyGroupAssignmentsToUser(
                        eq(DIR_ID), eq(profileId), anyString(), any()))
                .willReturn(new com.ldapportal.service.ProvisioningProfileService
                        .GroupAssignmentResult(1, List.of(
                                "Not added to cn=AllEmployees,ou=Groups,dc=x: schema violation")));

        CreateEntryRequest req = new CreateEntryRequest(ENTRY_DN,
                Map.of("cn", List.of("Alice"), "sn", List.of("Smith")));

        mockMvc.perform(post(BASE_URL)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entry.dn").value(ENTRY_DN))
                .andExpect(jsonPath("$.groupsAdded").value(1))
                .andExpect(jsonPath("$.groupWarnings[0]").value(
                        org.hamcrest.Matchers.containsString("cn=AllEmployees")));
    }

    @Test
    void createUser_attributeValidationFailure_returns400WithDetail() throws Exception {
        // A malformed DN-valued attribute (manager) is rejected by the service
        // with IllegalArgumentException; pin that it surfaces as a 400
        // ProblemDetail carrying the field-level message (not a 500), which is
        // the contract the create form keys its inline errors off.
        CreateEntryRequest req = new CreateEntryRequest(ENTRY_DN,
                Map.of("cn", List.of("Alice"), "manager", List.of("not a dn")));
        given(ldapService.createUser(eq(DIR_ID), any(), any(), any()))
                .willThrow(new IllegalArgumentException("Attribute [manager] is not a valid DN: not a dn"));

        mockMvc.perform(post(BASE_URL)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Attribute [manager] is not a valid DN: not a dn"));
    }

    @Test
    void createUser_dnOutsideProfileDit_returns400() throws Exception {
        // The DN is admin-editable on the create form; when a profile matches the
        // target DN, the controller re-asserts the (possibly overridden) DN stays
        // within the profile's target OU. Pin that the service's rejection surfaces
        // as a 400 ProblemDetail rather than a 500.
        UUID profileId = UUID.fromString("40000000-0000-0000-0000-000000000004");
        com.ldapportal.entity.ProvisioningProfile profile =
                org.mockito.Mockito.mock(com.ldapportal.entity.ProvisioningProfile.class);
        given(profile.getId()).willReturn(profileId);
        given(approvalService.findProfileForDn(eq(DIR_ID), anyString()))
                .willReturn(java.util.Optional.of(profile));
        org.mockito.BDDMockito.willThrow(new IllegalArgumentException(
                        "User DN [" + ENTRY_DN + "] is outside the profile's target OU [ou=staff,dc=example,dc=com]"))
                .given(provisioningProfileService).requireDnWithinProfileDit(eq(profileId), anyString());

        CreateEntryRequest req = new CreateEntryRequest(ENTRY_DN,
                Map.of("cn", List.of("Alice"), "sn", List.of("Smith")));

        mockMvc.perform(post(BASE_URL)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
                        "outside the profile's target OU")));
    }

    // ── GET /entry ────────────────────────────────────────────────────────────

    @Test
    void getUser_byDn_returns200() throws Exception {
        given(ldapService.getUser(eq(DIR_ID), any(), eq(ENTRY_DN), any()))
                .willReturn(sampleEntry());

        mockMvc.perform(get(BASE_URL + "/entry")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dn").value(ENTRY_DN))
                .andExpect(jsonPath("$.attributes.cn[0]").value("Alice"));
    }

    @Test
    void getUser_notFound_returns404() throws Exception {
        given(ldapService.getUser(eq(DIR_ID), any(), eq(ENTRY_DN), any()))
                .willThrow(new ResourceNotFoundException("Entry not found"));

        mockMvc.perform(get(BASE_URL + "/entry")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth())))
                .andExpect(status().isNotFound());
    }

    // ── PUT /entry ────────────────────────────────────────────────────────────

    @Test
    void updateUser_admin_returns200() throws Exception {
        UpdateEntryRequest req = new UpdateEntryRequest(
                List.of(new AttributeModification(AttributeModification.Operation.REPLACE,
                        "mail", List.of("newalice@example.com"))));
        // No If-Unmodified-Since-LDAP header → unconditional update (null
        // precondition forwarded to the service).
        given(ldapService.updateUser(eq(DIR_ID), any(), eq(ENTRY_DN), any(), isNull()))
                .willReturn(sampleEntry());

        mockMvc.perform(put(BASE_URL + "/entry")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    void updateUser_stalePrecondition_returns412() throws Exception {
        // The inline-edit table sends the modifyTimestamp it loaded; a
        // mismatch must surface as 412 Precondition Failed so the row can
        // offer "reload" instead of overwriting a concurrent change.
        UpdateEntryRequest req = new UpdateEntryRequest(
                List.of(new AttributeModification(AttributeModification.Operation.REPLACE,
                        "mail", List.of("newalice@example.com"))));
        given(ldapService.updateUser(eq(DIR_ID), any(), eq(ENTRY_DN), any(), eq("20260612180000Z")))
                .willThrow(new com.ldapportal.exception.PreconditionFailedException(
                        "Entry [" + ENTRY_DN + "] changed since it was loaded"));

        mockMvc.perform(put(BASE_URL + "/entry")
                        .param("dn", ENTRY_DN)
                        .header("If-Unmodified-Since-LDAP", "20260612180000Z")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("changed since it was loaded")));
    }

    // ── DELETE /entry ─────────────────────────────────────────────────────────

    @Test
    void deleteUser_admin_returns204() throws Exception {
        willDoNothing().given(ldapService).deleteUser(eq(DIR_ID), any(), eq(ENTRY_DN));

        mockMvc.perform(delete(BASE_URL + "/entry")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth())))
                .andExpect(status().isNoContent());
    }

    // ── POST /enable & /disable ───────────────────────────────────────────────

    @Test
    void enableUser_admin_returns204() throws Exception {
        willDoNothing().given(ldapService).enableUser(eq(DIR_ID), any(), eq(ENTRY_DN));

        mockMvc.perform(post(BASE_URL + "/enable")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth())))
                .andExpect(status().isNoContent());
    }

    @Test
    void disableUser_admin_returns204() throws Exception {
        willDoNothing().given(ldapService).disableUser(eq(DIR_ID), any(), eq(ENTRY_DN));

        mockMvc.perform(post(BASE_URL + "/disable")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth())))
                .andExpect(status().isNoContent());
    }

    // ── POST /move ────────────────────────────────────────────────────────────

    @Test
    void moveUser_admin_returns204() throws Exception {
        UUID destProfile = UUID.fromString("30000000-0000-0000-0000-000000000003");
        given(provisioningProfileService.get(DIR_ID, destProfile)).willReturn(moveDestProfile(destProfile));
        MoveUserRequest req = new MoveUserRequest(destProfile);
        willDoNothing().given(ldapService).moveUser(eq(DIR_ID), any(), eq(ENTRY_DN), any());

        mockMvc.perform(post(BASE_URL + "/move")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNoContent());
    }

    @Test
    void moveUser_withoutDestinationProfileAccess_returns403() throws Exception {
        UUID destProfile = UUID.fromString("30000000-0000-0000-0000-000000000004");
        given(provisioningProfileService.get(DIR_ID, destProfile)).willReturn(moveDestProfile(destProfile));
        willThrow(new org.springframework.security.access.AccessDeniedException("No access"))
                .given(permissionService).requireProfileAccess(any(), eq(destProfile));
        MoveUserRequest req = new MoveUserRequest(destProfile);

        mockMvc.perform(post(BASE_URL + "/move")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    /** Minimal ProfileResponse stub for the move destination. */
    private com.ldapportal.dto.profile.ProfileResponse moveDestProfile(UUID id) {
        return new com.ldapportal.dto.profile.ProfileResponse(
                id, DIR_ID, "dir-1", "Staff", null,
                "ou=staff,dc=example,dc=com", "ou=groups,dc=example,dc=com",
                List.of("inetOrgPerson"), "uid",
                true, null, null, null, null, true, false,
                16, true, true, true, true, "!@#$%^&*", false,
                "OPERATOR_ENTERED", false, false,
                List.of(), List.of(), List.of(), List.of(), null, null);
    }

    // ── POST /memberships ─────────────────────────────────────────────────────

    static final String GROUP_A = "cn=devs,ou=groups,dc=example,dc=com";
    static final String GROUP_B = "cn=ops,ou=groups,dc=example,dc=com";

    @Test
    void applyMemberships_admin_returns200_withSummary() throws Exception {
        MembershipChangeRequest req = new MembershipChangeRequest(List.of(
                new Change(GROUP_A, "member", Op.ADD),
                new Change(GROUP_B, "member", Op.REMOVE)));

        MembershipChangeResult result = MembershipChangeResult.of(List.of(
                MembershipChangeResult.Item.applied(new Change(GROUP_B, "member", Op.REMOVE)),
                MembershipChangeResult.Item.queued(new Change(GROUP_A, "member", Op.ADD),
                        UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"))));
        given(ldapService.applyMembershipChanges(eq(DIR_ID), any(), eq(ENTRY_DN), any()))
                .willReturn(result);

        mockMvc.perform(post(BASE_URL + "/memberships")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.queued").value(1))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].status").value("QUEUED_FOR_APPROVAL"))
                .andExpect(jsonPath("$.items[1].approvalId")
                        .value("aaaaaaaa-0000-0000-0000-000000000001"));
    }

    @Test
    void applyMemberships_emptyChanges_returns400() throws Exception {
        MembershipChangeRequest req = new MembershipChangeRequest(List.of());

        mockMvc.perform(post(BASE_URL + "/memberships")
                        .param("dn", ENTRY_DN)
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void applyMemberships_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post(BASE_URL + "/memberships")
                        .param("dn", ENTRY_DN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"changes\":[]}"))
                .andExpect(status().isUnauthorized());
    }
}
