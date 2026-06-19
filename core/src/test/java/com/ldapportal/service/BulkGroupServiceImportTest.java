// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.BulkImportRowResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapGroupService;
import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link BulkGroupService#importCsv} handling of a group that already
 * exists (matched by cn + parent DN): the row is treated as a bulk member-add,
 * merging the CSV's members into the existing group rather than skipping.
 */
@ExtendWith(MockitoExtension.class)
class BulkGroupServiceImportTest {

    @Mock private LdapGroupService groupService;

    private BulkGroupService service;
    private DirectoryConnection dc;

    private static final String GROUP_DN = "cn=engineering,ou=groups,dc=example,dc=com";

    @BeforeEach
    void setUp() {
        service = new BulkGroupService(groupService);
        dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setBaseDn("dc=example,dc=com");
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private LdapOperationException entryAlreadyExists() {
        return new LdapOperationException(
                "createGroup failed: " + ResultCode.ENTRY_ALREADY_EXISTS.getName());
    }

    @Test
    void importCsv_existingGroup_mergesMembers_evenWithSkipConflictHandling() throws IOException {
        // The group already exists, so the create fails with ENTRY_ALREADY_EXISTS.
        doThrow(entryAlreadyExists()).when(groupService).createGroup(any(), anyString(), any());
        String content = "cn,members\nengineering,uid=alice|uid=bob\n";

        BulkImportResult result = service.importCsv(
                dc, csv(content), "ou=groups,dc=example,dc=com",
                ConflictHandling.SKIP, List.of(), List.of("groupOfNames"), "member", true);

        // Both members are added to the existing group even though conflict
        // handling is SKIP — the old behaviour would have skipped the row.
        verify(groupService).addMember(dc, GROUP_DN, "member", "uid=alice");
        verify(groupService).addMember(dc, GROUP_DN, "member", "uid=bob");
        verify(groupService, never()).updateGroup(any(), anyString(), any());
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.rows().get(0).status()).isEqualTo(BulkImportRowResult.Status.UPDATED);
    }

    @Test
    void importCsv_existingGroup_noMembers_skips() throws IOException {
        doThrow(entryAlreadyExists()).when(groupService).createGroup(any(), anyString(), any());
        String content = "cn,description\nengineering,Eng team\n";

        BulkImportResult result = service.importCsv(
                dc, csv(content), "ou=groups,dc=example,dc=com",
                ConflictHandling.SKIP, List.of(), List.of("groupOfNames"), "member", true);

        // Nothing to merge and not OVERWRITE → the existing group is untouched.
        verify(groupService, never()).addMember(any(), anyString(), anyString(), anyString());
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.rows().get(0).status()).isEqualTo(BulkImportRowResult.Status.SKIPPED);
    }
}
