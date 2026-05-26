// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.BulkImportRowResult;
import com.ldapportal.dto.csv.CsvColumnMappingDto;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapUserService;
import com.ldapportal.ldap.model.LdapUser;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BulkUserServiceTest {

    @Mock private LdapUserService userService;

    private BulkUserService service;
    private DirectoryConnection dc;

    @BeforeEach
    void setUp() {
        // Profile-aware import is opt-in; pass a null-returning provider
        // so the existing tests exercise the no-profile-context path
        // (which is byte-identical to pre-profile-aware behaviour).
        // lenient() because most tests don't trigger the profile lookup
        // path at all (they pass null context via the legacy importCsv
        // overload), and strict mode would flag the stub as unused.
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<ProvisioningProfileService> nullProvider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.lenient().when(nullProvider.getIfAvailable()).thenReturn(null);
        service = new BulkUserService(userService, nullProvider);
        dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setBaseDn("dc=example,dc=com");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private LdapUser ldapUser(String dn, Map<String, List<String>> attrs) {
        return new LdapUser(dn, attrs);
    }

    /** Creates a LdapOperationException that looks like ENTRY_ALREADY_EXISTS. */
    private LdapOperationException entryAlreadyExists(String dn) {
        return new LdapOperationException(
                "createUser failed for [" + dn + "]: "
                + ResultCode.ENTRY_ALREADY_EXISTS.getName());
    }

    // ── Import — create ───────────────────────────────────────────────────────

    @Test
    void importCsv_createsNewEntries() throws IOException {
        String csvContent = "uid,cn,mail\njsmith,John Smith,jsmith@example.com\n";
        // createUser succeeds (default void mock behaviour) → entry created

        BulkImportResult result = service.importCsv(
                dc, csv(csvContent),
                "ou=people,dc=example,dc=com",
                "uid",
                ConflictHandling.SKIP,
                List.of(), List.of(), true);

        assertThat(result.totalRows()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(0);
        assertThat(result.errors()).isEqualTo(0);
        assertThat(result.rows().get(0).status()).isEqualTo(BulkImportRowResult.Status.CREATED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> attrsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(userService).createUser(eq(dc),
                eq("uid=jsmith,ou=people,dc=example,dc=com"),
                attrsCaptor.capture());
        assertThat(attrsCaptor.getValue()).containsKey("cn");
        assertThat(attrsCaptor.getValue().get("mail")).containsExactly("jsmith@example.com");
    }

    @Test
    void importCsv_skipsExistingEntry_whenConflictHandlingSkip() throws IOException {
        String csvContent = "uid,cn\nexisting,Existing User\n";
        // createUser throws ENTRY_ALREADY_EXISTS → triggers conflict handling
        doThrow(entryAlreadyExists("uid=existing,ou=people,dc=example,dc=com"))
                .when(userService).createUser(eq(dc), anyString(), any());

        BulkImportResult result = service.importCsv(
                dc, csv(csvContent),
                "ou=people,dc=example,dc=com",
                "uid",
                ConflictHandling.SKIP,
                List.of(), List.of(), true);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(0);
        assertThat(result.rows().get(0).status()).isEqualTo(BulkImportRowResult.Status.SKIPPED);
        verify(userService, never()).updateUser(any(), anyString(), any());
    }

    @Test
    void importCsv_updatesExistingEntry_whenConflictHandlingOverwrite() throws IOException {
        String csvContent = "uid,cn,mail\nexisting,Updated Name,new@example.com\n";
        // createUser throws ENTRY_ALREADY_EXISTS → triggers OVERWRITE path
        doThrow(entryAlreadyExists("uid=existing,ou=people,dc=example,dc=com"))
                .when(userService).createUser(eq(dc), anyString(), any());

        BulkImportResult result = service.importCsv(
                dc, csv(csvContent),
                "ou=people,dc=example,dc=com",
                "uid",
                ConflictHandling.OVERWRITE,
                List.of(), List.of(), true);

        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.rows().get(0).status()).isEqualTo(BulkImportRowResult.Status.UPDATED);
        verify(userService).updateUser(eq(dc), anyString(), any());
    }

    @Test
    void importCsv_errorRow_whenKeyAttributeMissing() throws IOException {
        // uid column exists in header but has an empty value in this row
        String csvContent = "uid,cn\n,John Smith\n";

        BulkImportResult result = service.importCsv(
                dc, csv(csvContent),
                "ou=people,dc=example,dc=com",
                "uid",
                ConflictHandling.SKIP,
                List.of(), List.of(), true);

        assertThat(result.errors()).isEqualTo(1);
        assertThat(result.rows().get(0).status()).isEqualTo(BulkImportRowResult.Status.ERROR);
        assertThat(result.rows().get(0).message()).contains("uid");
        verify(userService, never()).createUser(any(), anyString(), any());
    }

    @Test
    void importCsv_multipleRows_countsCorrectly() throws IOException {
        String csvContent = "uid,cn\nnew1,New One\nnew2,New Two\nexist,Existing\n";

        // new1, new2 → createUser succeeds; exist → ENTRY_ALREADY_EXISTS
        doNothing().when(userService).createUser(eq(dc),
                eq("uid=new1,ou=p,dc=example,dc=com"), any());
        doNothing().when(userService).createUser(eq(dc),
                eq("uid=new2,ou=p,dc=example,dc=com"), any());
        doThrow(entryAlreadyExists("uid=exist,ou=p,dc=example,dc=com"))
                .when(userService).createUser(eq(dc),
                        eq("uid=exist,ou=p,dc=example,dc=com"), any());

        BulkImportResult result = service.importCsv(
                dc, csv(csvContent),
                "ou=p,dc=example,dc=com",
                "uid",
                ConflictHandling.SKIP,
                List.of(), List.of(), true);

        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).isEqualTo(0);
    }

    // ── Import — column mappings ───────────────────────────────────────────────

    @Test
    void importCsv_columnMappings_mapsHeadersToLdapAttributes() throws IOException {
        String csvContent = "User Login,Full Name\njsmith,John Smith\n";
        // createUser succeeds (default void mock behaviour)

        List<CsvColumnMappingDto> mappings = List.of(
                new CsvColumnMappingDto("User Login", "uid", false),
                new CsvColumnMappingDto("Full Name", "cn", false));

        service.importCsv(dc, csv(csvContent),
                "ou=people,dc=example,dc=com", "uid", ConflictHandling.SKIP, mappings, List.of(), true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(userService).createUser(eq(dc),
                eq("uid=jsmith,ou=people,dc=example,dc=com"), captor.capture());
        assertThat(captor.getValue()).containsKey("cn");
        assertThat(captor.getValue().get("cn")).containsExactly("John Smith");
    }

    @Test
    void importCsv_ignoredColumn_notIncludedInAttributes() throws IOException {
        String csvContent = "uid,password,cn\njsmith,secret,John\n";
        // createUser succeeds (default void mock behaviour)

        List<CsvColumnMappingDto> mappings = List.of(
                new CsvColumnMappingDto("uid",      "uid",  false),
                new CsvColumnMappingDto("password", null,   true),   // ignored
                new CsvColumnMappingDto("cn",       "cn",   false));

        service.importCsv(dc, csv(csvContent),
                "ou=people,dc=example,dc=com", "uid", ConflictHandling.SKIP, mappings, List.of(), true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(userService).createUser(any(), anyString(), captor.capture());
        assertThat(captor.getValue()).doesNotContainKey("password");
        assertThat(captor.getValue()).containsKey("cn");
    }

    // ── Export ────────────────────────────────────────────────────────────────

    @Test
    void exportCsv_returnsHeaderAndDataRows() throws IOException {
        LdapUser user = ldapUser("uid=jsmith,ou=people,dc=example,dc=com",
                Map.of("cn",   List.of("John Smith"),
                       "mail", List.of("jsmith@example.com")));

        // When attributes are specified, exportCsv uses processUsers (streaming)
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<LdapUser> consumer = invocation.getArgument(3);
            consumer.accept(user);
            return null;
        }).when(userService).processUsers(eq(dc), anyString(), isNull(), any(), eq("cn"), eq("mail"));

        byte[] csv = service.exportCsv(dc, null, null, List.of("cn", "mail"));

        String output = new String(csv, StandardCharsets.UTF_8);
        assertThat(output).contains("\"dn\",\"cn\",\"mail\"");
        assertThat(output).contains("uid=jsmith,ou=people,dc=example,dc=com");
        assertThat(output).contains("John Smith");
        assertThat(output).contains("jsmith@example.com");
    }

    @Test
    void exportCsv_multiValuedAttribute_pipeJoined() throws IOException {
        LdapUser user = ldapUser("uid=jsmith,ou=people,dc=example,dc=com",
                Map.of("memberof", List.of("cn=admins,dc=example,dc=com",
                                           "cn=users,dc=example,dc=com")));

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<LdapUser> consumer = invocation.getArgument(3);
            consumer.accept(user);
            return null;
        }).when(userService).processUsers(eq(dc), anyString(), isNull(), any(), eq("memberof"));

        byte[] csv = service.exportCsv(dc, null, null, List.of("memberof"));

        String output = new String(csv, StandardCharsets.UTF_8);
        assertThat(output).contains("cn=admins,dc=example,dc=com|cn=users,dc=example,dc=com");
    }

    @Test
    void exportCsv_emptyResult_returnsHeaderOnly() throws IOException {
        // When attributes are specified, exportCsv uses processUsers (streaming)
        // processUsers does nothing (no entries) → only header is written
        doNothing().when(userService).processUsers(eq(dc), anyString(), isNull(), any(), eq("cn"), eq("mail"));

        byte[] csv = service.exportCsv(dc, null, null, List.of("cn", "mail"));

        String output = new String(csv, StandardCharsets.UTF_8);
        String[] lines = output.split("\n");
        assertThat(lines[0]).contains("dn");
        assertThat(lines[0]).contains("cn");
    }

    @Test
    void exportCsv_defaultFilter_usedWhenFilterNull() throws IOException {
        // When attributes are specified, exportCsv uses processUsers (streaming)
        doNothing().when(userService).processUsers(eq(dc), eq("(objectClass=*)"), isNull(), any(), eq("cn"));

        service.exportCsv(dc, null, null, List.of("cn"));

        verify(userService).processUsers(eq(dc), eq("(objectClass=*)"), isNull(), any(), eq("cn"));
    }

    // ── Preview — required-attribute validation ──────────────────────────────

    @Test
    void previewImport_flagsRowsMissingRequiredAttrs() throws IOException {
        // Two-row CSV: row 1 is complete (uid+cn+sn), row 2 has a blank sn.
        // sn is required, so row 2 should report it as missing.
        String csv = "uid,cn,sn\nalice,Alice Smith,Smith\nbob,Bob,\n";
        var result = service.previewImport(
                csv(csv),
                "ou=people,dc=example,dc=com",
                "uid",
                List.of(),  // passthrough — header IS the LDAP attribute
                true,
                List.of("sn"));

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.rows().get(0).missingRequired()).isEmpty();
        assertThat(result.rows().get(1).missingRequired()).containsExactly("sn");
    }

    @Test
    void previewImport_emptyRequiredList_neverFlags() throws IOException {
        String csv = "uid,cn\nalice,Alice\nbob,\n";
        var result = service.previewImport(
                csv(csv),
                "ou=people,dc=example,dc=com",
                "uid", List.of(), true, List.of());

        assertThat(result.rows()).allSatisfy(row ->
                assertThat(row.missingRequired()).isEmpty());
    }

    @Test
    void previewImport_caseInsensitiveAttrMatching() throws IOException {
        // CSV header uses lowercase 'sn'; required list uses 'SN'.
        // LDAP attribute names are case-insensitive — match should still hit.
        String csv = "uid,cn,sn\nalice,Alice,Smith\n";
        var result = service.previewImport(
                csv(csv),
                "ou=people,dc=example,dc=com",
                "uid", List.of(), true, List.of("SN"));

        assertThat(result.rows().get(0).missingRequired()).isEmpty();
    }

    @Test
    void previewImport_legacyOverload_usesEmptyRequired() throws IOException {
        // The 5-arg overload is the backwards-compat path used by tests and
        // any caller that doesn't have schema info. Should always produce
        // empty missingRequired regardless of row content.
        String csv = "uid\nalice\n";
        var result = service.previewImport(
                csv(csv),
                "ou=people,dc=example,dc=com",
                "uid", List.of(), true);

        assertThat(result.rows().get(0).missingRequired()).isEmpty();
    }

    @Test
    void previewImport_missingRdn_flaggedAsMissingRequired() throws IOException {
        // Row 2 has no uid. uid is the RDN/key attribute, special-cased in
        // the orchestrator's requiredAttrs filter, but at preview time we
        // still want it surfaced through missingRequired so the row picks
        // up the amber highlight + Issues column + banner count alongside
        // schema-required rows.
        String csv = "uid,cn,sn\nalice,Alice Smith,Smith\n,Bob Bobson,Bobson\n";
        var result = service.previewImport(
                csv(csv),
                "ou=people,dc=example,dc=com",
                "uid", List.of(), true,
                List.of("sn"));

        assertThat(result.rows().get(0).computedDn()).isNotNull();
        assertThat(result.rows().get(0).missingRequired()).isEmpty();

        assertThat(result.rows().get(1).computedDn()).isNull();
        assertThat(result.rows().get(1).missingRequired()).containsExactly("uid");
    }

    @Test
    void previewImport_missingRdnAndSchemaAttr_bothListed() throws IOException {
        // Row missing both uid AND sn. The RDN comes first so it reads as
        // the proximate cause when the user scans the column.
        String csv = "uid,cn,sn\n,Bob Bobson,\n";
        var result = service.previewImport(
                csv(csv),
                "ou=people,dc=example,dc=com",
                "uid", List.of(), true,
                List.of("sn"));

        assertThat(result.rows().get(0).missingRequired()).containsExactly("uid", "sn");
    }
}
