// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.directory;

import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.ldap.LdapBrowseService.BrowseResult;
import com.ldapportal.service.LdapOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice tests for the directory-scoped, read-only {@code GET /browse}:
 * the {@code limit} parameter the DN quick-look drawer uses to fetch just an
 * entry's attributes, its default, and the 400 mapping for a negative value.
 */
@WebMvcTest(DirectoryBrowseController.class)
class DirectoryBrowseControllerTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private LdapOperationService service;

    private static final UUID DIR_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final String URL = "/api/v1/directories/" + DIR_ID + "/browse";
    private static final String DN = "uid=alice,ou=people,dc=example,dc=com";

    private static BrowseResult entry() {
        return new BrowseResult(DN, Map.of("uid", List.of("alice")), List.of(), false, 0, false);
    }

    @Test
    void browse_passesLimit() throws Exception {
        when(service.browse(eq(DIR_ID), any(), eq(DN), eq(1))).thenReturn(entry());

        mockMvc.perform(get(URL).param("dn", DN).param("limit", "1")
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dn").value(DN))
                .andExpect(jsonPath("$.attributes.uid[0]").value("alice"));

        verify(service).browse(eq(DIR_ID), any(), eq(DN), eq(1));
    }

    @Test
    void browse_withoutLimit_returnsAllChildren() throws Exception {
        when(service.browse(eq(DIR_ID), any(), eq(DN), eq(0))).thenReturn(entry());

        mockMvc.perform(get(URL).param("dn", DN).with(authentication(adminAuth())))
                .andExpect(status().isOk());

        verify(service).browse(eq(DIR_ID), any(), eq(DN), eq(0));
    }

    @Test
    void browse_negativeLimit_is400() throws Exception {
        mockMvc.perform(get(URL).param("dn", DN).param("limit", "-1")
                        .with(authentication(adminAuth())))
                .andExpect(status().isBadRequest());

        verify(service, never()).browse(any(), any(), anyString(), anyInt());
    }

    @Test
    void browse_unauthenticated_is401() throws Exception {
        mockMvc.perform(get(URL).param("dn", DN))
                .andExpect(status().isUnauthorized());
    }
}
