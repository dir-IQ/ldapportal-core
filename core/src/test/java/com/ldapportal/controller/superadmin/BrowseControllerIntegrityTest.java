// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.dto.ldap.RemoveBrokenReferencesResult;
import com.ldapportal.dto.ldap.RemoveBrokenReferencesResult.Outcome;
import com.ldapportal.dto.ldap.RemoveBrokenReferencesResult.Status;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.AuditAction;
import com.ldapportal.ldap.IntegrityCheckService;
import com.ldapportal.ldap.LdapBrowseService;
import com.ldapportal.ldap.LdapSchemaService;
import com.ldapportal.ldap.LdifPreviewService;
import com.ldapportal.ldap.LdifService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web-slice tests for removing broken member references found by an integrity check. */
@WebMvcTest(controllers = BrowseController.class)
class BrowseControllerIntegrityTest extends BaseControllerTest {

    private static final String URL =
            "/api/v1/superadmin/directories/{id}/browse/integrity-check/remove-broken-references";
    private static final String BODY = """
            {"references":[
              {"groupDn":"cn=staff,dc=example,dc=com","attribute":"member","value":"uid=ghost,dc=example,dc=com"},
              {"groupDn":"cn=staff,dc=example,dc=com","attribute":"member","value":"uid=back,dc=example,dc=com"}]}
            """;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private LdapBrowseService browseService;
    @MockitoBean private LdapSchemaService schemaService;
    @MockitoBean private LdifService ldifService;
    @MockitoBean private LdifPreviewService ldifPreviewService;
    @MockitoBean private IntegrityCheckService integrityCheckService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private DirectoryConnectionRepository dirRepo;

    private final UUID dirId = UUID.randomUUID();

    private void directoryExists() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(dirId);
        dc.setBaseDn("dc=example,dc=com");
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dc));
    }

    @Test
    void removesTheReferences_andAuditsEachOneRemoved() throws Exception {
        directoryExists();
        when(integrityCheckService.removeBrokenReferences(any(), any())).thenReturn(
                new RemoveBrokenReferencesResult(1, List.of(
                        new Outcome("cn=staff,dc=example,dc=com", "member", "uid=ghost,dc=example,dc=com",
                                Status.REMOVED, null),
                        new Outcome("cn=staff,dc=example,dc=com", "member", "uid=back,dc=example,dc=com",
                                Status.KEPT_ENTRY_EXISTS, "An entry exists at this DN now."))));

        mockMvc.perform(post(URL, dirId).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(1))
                .andExpect(jsonPath("$.outcomes[1].status").value("KEPT_ENTRY_EXISTS"));

        verify(auditService).record(any(), eq(dirId), eq(AuditAction.GROUP_MEMBER_REMOVE),
                eq("cn=staff,dc=example,dc=com"), eq(Map.of("member", "uid=ghost,dc=example,dc=com",
                        "attribute", "member", "source", "integrity-check",
                        "operation", "remove-broken-reference")));
        verify(auditService, never()).record(any(), any(), any(), any(),
                eq(Map.of("member", "uid=back,dc=example,dc=com", "attribute", "member",
                        "source", "integrity-check", "operation", "remove-broken-reference")));
    }

    @Test
    void anotherAttribute_returns400() throws Exception {
        directoryExists();
        when(integrityCheckService.removeBrokenReferences(any(), any()))
                .thenThrow(new IllegalArgumentException("Only member and uniqueMember references can be removed"));

        mockMvc.perform(post(URL, dirId).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noReferences_returns400() throws Exception {
        directoryExists();

        mockMvc.perform(post(URL, dirId).contentType(MediaType.APPLICATION_JSON).content("{\"references\":[]}")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresSuperadmin() throws Exception {
        mockMvc.perform(post(URL, dirId).contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }
}
