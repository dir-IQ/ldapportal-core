// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.BulkImportRowResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapGroupService;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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

    // ── dn column ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    void importCsv_exportedDnColumn_isIgnoredNotWrittenAsAttribute() throws IOException {
        // Group export writes a dn column; re-importing it as-is used to send
        // "dn" as an attribute and fail the add.
        String content = "dn,cn,description,member\n"
                + "\"cn=engineering,ou=groups,dc=example,dc=com\",engineering,Eng team,uid=alice\n";

        BulkImportResult result = service.importCsv(
                dc, csv(content), "ou=groups,dc=example,dc=com",
                ConflictHandling.SKIP, List.of(), List.of("groupOfNames"), "member", true);

        ArgumentCaptor<Map<String, List<String>>> attrs = ArgumentCaptor.forClass(Map.class);
        verify(groupService).createGroup(eq(dc), eq(GROUP_DN), attrs.capture());
        assertThat(attrs.getValue()).doesNotContainKey("dn")
                .containsEntry("description", List.of("Eng team"))
                .containsEntry("member", List.of("uid=alice"));
        assertThat(result.created()).isEqualTo(1);
    }

    // ── member-operation ──────────────────────────────────────────────────────

    private LdapOperationException ldapFailure(ResultCode code) {
        return new LdapOperationException("LDAP operation failed: " + code.getName(),
                new LDAPException(code, code.getName()));
    }

    @Test
    void importCsv_memberOperationDelete_removesMembersWithoutCreating() throws IOException {
        String content = "cn,members,member-operation\nengineering,uid=alice|uid=bob,delete\n";

        BulkImportResult result = service.importCsv(
                dc, csv(content), "ou=groups,dc=example,dc=com",
                ConflictHandling.SKIP, List.of(), List.of("groupOfNames"), "member", true);

        verify(groupService, never()).createGroup(any(), anyString(), any());
        verify(groupService).removeMember(dc, GROUP_DN, "member", "uid=alice");
        verify(groupService).removeMember(dc, GROUP_DN, "member", "uid=bob");
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.rows().get(0).message()).isEqualTo("Removed 2 member(s)");
    }

    @Test
    void importCsv_memberOperationDelete_skipsValuesThatAreNotMembers() throws IOException {
        // Only uid=bob is stubbed; lenient so the unstubbed uid=alice call
        // isn't flagged by strict stubs as an argument mismatch.
        lenient().doThrow(ldapFailure(ResultCode.NO_SUCH_ATTRIBUTE))
                .when(groupService).removeMember(dc, GROUP_DN, "member", "uid=bob");
        String content = "cn,members,member-operation\nengineering,uid=alice|uid=bob,DELETE\n";

        BulkImportResult result = service.importCsv(
                dc, csv(content), "ou=groups,dc=example,dc=com",
                ConflictHandling.SKIP, List.of(), List.of("groupOfNames"), "member", true);

        assertThat(result.rows().get(0).status()).isEqualTo(BulkImportRowResult.Status.UPDATED);
        assertThat(result.rows().get(0).message()).isEqualTo("Removed 1 member(s); 1 not present");
    }

    @Test
    void importCsv_memberOperationDelete_noneAreMembers_skips() throws IOException {
        doThrow(ldapFailure(ResultCode.NO_SUCH_ATTRIBUTE))
                .when(groupService).removeMember(any(), anyString(), anyString(), anyString());
        String content = "cn,members,member-operation\nengineering,uid=alice,delete\n";

        BulkImportResult result = service.importCsv(
                dc, csv(content), "ou=groups,dc=example,dc=com",
                ConflictHandling.SKIP, List.of(), List.of("groupOfNames"), "member", true);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.rows().get(0).message()).isEqualTo("Members not present in group");
    }

    @Test
    void importCsv_memberOperationDelete_missingGroup_isAnError() throws IOException {
        doThrow(ldapFailure(ResultCode.NO_SUCH_OBJECT))
                .when(groupService).removeMember(any(), anyString(), anyString(), anyString());
        String content = "cn,members,member-operation\nengineering,uid=alice,delete\n";

        BulkImportResult result = service.importCsv(
                dc, csv(content), "ou=groups,dc=example,dc=com",
                ConflictHandling.SKIP, List.of(), List.of("groupOfNames"), "member", true);

        verify(groupService, never()).createGroup(any(), anyString(), any());
        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.rows().get(0).message()).isEqualTo("Group does not exist");
    }

    @Test
    void importCsv_memberOperationDelete_rejectedRemoval_isAnErrorNamingTheMember() throws IOException {
        // e.g. removing the last member of a groupOfNames
        // Only uid=bob is stubbed; lenient so the unstubbed uid=alice call
        // isn't flagged by strict stubs as an argument mismatch.
        lenient().doThrow(ldapFailure(ResultCode.OBJECT_CLASS_VIOLATION))
                .when(groupService).removeMember(dc, GROUP_DN, "member", "uid=bob");
        String content = "cn,members,member-operation\nengineering,uid=alice|uid=bob,delete\n";

        BulkImportResult result = service.importCsv(
                dc, csv(content), "ou=groups,dc=example,dc=com",
                ConflictHandling.SKIP, List.of(), List.of("groupOfNames"), "member", true);

        assertThat(result.rows().get(0).status()).isEqualTo(BulkImportRowResult.Status.ERROR);
        assertThat(result.rows().get(0).message()).startsWith("Removed 1 of 2 member(s); failed: uid=bob:");
    }

    @Test
    void importCsv_memberOperationDelete_withoutMembers_isAnError() throws IOException {
        String content = "cn,members,member-operation\nengineering,,delete\n";

        BulkImportResult result = service.importCsv(
                dc, csv(content), "ou=groups,dc=example,dc=com",
                ConflictHandling.SKIP, List.of(), List.of("groupOfNames"), "member", true);

        verify(groupService, never()).removeMember(any(), anyString(), anyString(), anyString());
        assertThat(result.errors()).isEqualTo(1);
    }

    @Test
    void importCsv_invalidMemberOperation_isAnError() throws IOException {
        String content = "cn,members,member-operation\nengineering,uid=alice,replace\n";

        BulkImportResult result = service.importCsv(
                dc, csv(content), "ou=groups,dc=example,dc=com",
                ConflictHandling.SKIP, List.of(), List.of("groupOfNames"), "member", true);

        verify(groupService, never()).createGroup(any(), anyString(), any());
        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.rows().get(0).message())
                .isEqualTo("Invalid member-operation 'replace': expected 'add' or 'delete'");
    }

    @SuppressWarnings("unchecked")
    @Test
    void importCsv_mixedOperations_eachRowFollowsItsOwnValue() throws IOException {
        String content = "cn,members,member-operation\n"
                + "engineering,uid=alice,add\n"
                + "sales,uid=bob,delete\n"
                + "support,uid=carol,\n";

        BulkImportResult result = service.importCsv(
                dc, csv(content), "ou=groups,dc=example,dc=com",
                ConflictHandling.SKIP, List.of(), List.of("groupOfNames"), "member", true);

        ArgumentCaptor<Map<String, List<String>>> attrs = ArgumentCaptor.forClass(Map.class);
        verify(groupService).createGroup(eq(dc), eq(GROUP_DN), attrs.capture());
        assertThat(attrs.getValue()).doesNotContainKey("member-operation");
        verify(groupService).removeMember(dc, "cn=sales,ou=groups,dc=example,dc=com", "member", "uid=bob");
        // A blank value means add.
        verify(groupService).createGroup(eq(dc), eq("cn=support,ou=groups,dc=example,dc=com"), any());
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.updated()).isEqualTo(1);
    }
}
