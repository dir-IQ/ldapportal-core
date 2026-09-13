// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller.superadmin;

import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.IntegrityCheckService;
import com.ldapportal.ldap.LdapBrowseService;
import com.ldapportal.ldap.LdapBrowseService.BrowseResult;
import com.ldapportal.ldap.LdapBrowseService.ChildEntry;
import com.ldapportal.ldap.LdapSchemaService;
import com.ldapportal.ldap.LdifPreviewService;
import com.ldapportal.ldap.LdifService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-slice tests for {@code GET /browse}: the {@code filter} / {@code limit}
 * parameters the directory browser's branch filter and load-all rely on,
 * their defaults, and the 400 mapping for bad input.
 */
@WebMvcTest(controllers = BrowseController.class)
class BrowseControllerBrowseTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private LdapBrowseService browseService;
    @MockitoBean private LdapSchemaService schemaService;
    @MockitoBean private LdifService ldifService;
    @MockitoBean private LdifPreviewService ldifPreviewService;
    @MockitoBean private IntegrityCheckService integrityCheckService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private DirectoryConnectionRepository dirRepo;

    private final UUID dirId = UUID.randomUUID();
    private static final String BASE = "dc=example,dc=com";

    private DirectoryConnection dir() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(dirId);
        dc.setBaseDn(BASE);
        return dc;
    }

    private static BrowseResult page(boolean truncated, Integer count, boolean approx) {
        return new BrowseResult(BASE, Map.of("objectClass", List.of("domain")),
                List.of(new ChildEntry("ou=people," + BASE, "ou=people", true)),
                truncated, count, approx);
    }

    @Test
    void browse_passesFilterAndLimit_andSerialisesPageMetadata() throws Exception {
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir()));
        when(browseService.browse(any(), eq(BASE), eq("smith"), eq(500)))
                .thenReturn(page(true, 12400, true));

        mockMvc.perform(get("/api/v1/superadmin/directories/{id}/browse", dirId)
                        .param("dn", BASE)
                        .param("filter", "smith")
                        .param("limit", "500")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.childCount").value(12400))
                .andExpect(jsonPath("$.childCountApproximate").value(true))
                .andExpect(jsonPath("$.children[0].rdn").value("ou=people"));
    }

    @Test
    void browse_withoutFilterOrLimit_asksForEverything() throws Exception {
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir()));
        when(browseService.browse(any(), isNull(), isNull(), eq(0)))
                .thenReturn(page(false, 1, false));

        mockMvc.perform(get("/api/v1/superadmin/directories/{id}/browse", dirId)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.childCount").value(1));
    }

    @Test
    void browse_negativeLimit_is400() throws Exception {
        mockMvc.perform(get("/api/v1/superadmin/directories/{id}/browse", dirId)
                        .param("limit", "-1")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isBadRequest());

        verify(browseService, never()).browse(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void browse_invalidRawFilter_is400ProblemDetail() throws Exception {
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir()));
        when(browseService.browse(any(), any(), eq("(cn="), eq(0)))
                .thenThrow(new IllegalArgumentException("Invalid LDAP filter: unbalanced"));

        mockMvc.perform(get("/api/v1/superadmin/directories/{id}/browse", dirId)
                        .param("filter", "(cn=")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid LDAP filter: unbalanced"));
    }

    @Test
    void deleteEntry_refreshListingIsBounded() throws Exception {
        when(dirRepo.findById(dirId)).thenReturn(Optional.of(dir()));
        // A full delete refreshes the parent; the courtesy listing is a
        // first page, never the whole branch.
        when(browseService.browse(any(), eq(BASE), isNull(), eq(BrowseController.REFRESH_CHILD_LIMIT)))
                .thenReturn(page(true, 12400, false));

        mockMvc.perform(delete("/api/v1/superadmin/directories/{id}/browse", dirId)
                        .param("dn", "ou=people," + BASE)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.truncated").value(true))
                .andExpect(jsonPath("$.childCount").value(12400));

        verify(browseService, never()).browse(any(), any());
    }

    @Test
    void browse_requiresSuperadmin() throws Exception {
        mockMvc.perform(get("/api/v1/superadmin/directories/{id}/browse", dirId)
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }
}
