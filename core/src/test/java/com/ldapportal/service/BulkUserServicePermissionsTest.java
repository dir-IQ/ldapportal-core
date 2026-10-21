// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.BulkImportPreviewResult;
import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.BulkImportRowResult;
import com.ldapportal.dto.csv.BulkImportRowResult.Status;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.entity.enums.ImportErrorHandling;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapUserService;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Row-level permission checks in {@link BulkUserService}: a row needing a
 * feature the admin lacks fails on its own; the other rows still run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BulkUserServicePermissionsTest {

    private static final String PARENT = "ou=people,dc=example,dc=com";
    private static final String EXISTING = "uid=existing," + PARENT;
    private static final String NEWBIE = "uid=newbie," + PARENT;

    @Mock private LdapUserService userService;

    private BulkUserService service;
    private DirectoryConnection dc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        org.springframework.beans.factory.ObjectProvider<ProvisioningProfileService> none =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        service = new BulkUserService(userService, none);
        dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setBaseDn("dc=example,dc=com");
        // "existing" is in the directory; anything else isn't.
        when(userService.entryExists(eq(dc), anyString())).thenAnswer(inv -> EXISTING.equals(inv.getArgument(1)));
        doThrow(new LdapOperationException("createUser failed: " + ResultCode.ENTRY_ALREADY_EXISTS.getName()))
                .when(userService).createUser(eq(dc), eq(EXISTING), any(), any());
    }

    private static BulkImportPermissions perms(boolean create, boolean edit, Map<String, String> protectedAttrs) {
        return new BulkImportPermissions(create, edit, protectedAttrs, true, true);
    }

    private BulkImportResult run(String csv, ConflictHandling conflict, BulkImportPermissions perms,
                                 ImportErrorHandling errors) throws IOException {
        return service.importCsv(dc, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), PARENT,
                "uid", conflict, List.of(), List.of(), true, null, null, List.of(), errors, ',', true,
                null, perms);
    }

    private BulkImportResult run(String csv, ConflictHandling conflict, BulkImportPermissions perms) throws IOException {
        return run(csv, conflict, perms, ImportErrorHandling.SKIP_ERRORS);
    }

    @Test
    void withoutCreateUsers_aNewUserFails_butAnExistingOneIsStillOverwritten() throws IOException {
        BulkImportResult result = run("uid,cn\nnewbie,New\nexisting,Changed\n",
                ConflictHandling.OVERWRITE, perms(false, true, Map.of()));

        assertThat(result.rows()).extracting(BulkImportRowResult::status).containsExactly(Status.ERROR, Status.UPDATED);
        assertThat(result.rows().get(0).message()).isEqualTo("Creating this user needs the 'Create users' permission");
        verify(userService, never()).createUser(any(), anyString(), any(), any());
        verify(userService).updateUser(eq(dc), eq(EXISTING), any());
    }

    @Test
    void withoutCreateUsers_anExistingUserIsSkippedUnderSkip() throws IOException {
        BulkImportResult result = run("uid,cn\nexisting,Changed\n", ConflictHandling.SKIP, perms(false, false, Map.of()));

        assertThat(result.rows().get(0).status()).isEqualTo(Status.SKIPPED);
        verify(userService, never()).updateUser(any(), anyString(), any());
    }

    @Test
    void withoutEditUsers_overwritingFails_butCreatingRuns() throws IOException {
        BulkImportResult result = run("uid,cn\nnewbie,New\nexisting,Changed\n",
                ConflictHandling.OVERWRITE, perms(true, false, Map.of()));

        assertThat(result.rows()).extracting(BulkImportRowResult::status).containsExactly(Status.CREATED, Status.ERROR);
        assertThat(result.rows().get(1).message()).isEqualTo("Updating this existing user needs the 'Edit users' permission");
        verify(userService).createUser(eq(dc), eq(NEWBIE), any(), any());
        verify(userService, never()).updateUser(any(), anyString(), any());
    }

    @Test
    void protectedAttribute_failsAnOverwrite_butNotACreate() throws IOException {
        BulkImportResult result = run("uid,userPassword\nnewbie,Secret1!\nexisting,Secret2!\n",
                ConflictHandling.OVERWRITE,
                perms(true, true, Map.of("userpassword", BulkImportPermissions.RESET_PASSWORDS)));

        // A new user's initial password is part of creating it.
        assertThat(result.rows()).extracting(BulkImportRowResult::status).containsExactly(Status.CREATED, Status.ERROR);
        assertThat(result.rows().get(1).message())
                .isEqualTo("Changing userPassword on an existing user needs the 'Reset user passwords' permission");
        verify(userService, never()).updateUser(any(), anyString(), any());
    }

    @Test
    void allPermissions_neverLooksUsersUp_andOverwritesAsBefore() throws IOException {
        BulkImportResult result = run("uid,cn\nexisting,Changed\n", ConflictHandling.OVERWRITE, BulkImportPermissions.ALL);

        assertThat(result.rows().get(0).status()).isEqualTo(Status.UPDATED);
        verify(userService, never()).entryExists(any(), anyString());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Modification>> mods = ArgumentCaptor.forClass(List.class);
        verify(userService).updateUser(eq(dc), eq(EXISTING), mods.capture());
        assertThat(mods.getValue()).extracting(Modification::getAttributeName).containsExactly("cn");
    }

    @Test
    void abortOnError_countsPermissionErrors_andWritesNothing() throws IOException {
        BulkImportResult result = run("uid,cn\nnewbie,New\nexisting,Changed\n",
                ConflictHandling.OVERWRITE, perms(false, true, Map.of()), ImportErrorHandling.ABORT_ON_ERROR);

        assertThat(result.rows()).extracting(BulkImportRowResult::status).containsExactly(Status.ERROR, Status.SKIPPED);
        assertThat(result.rows().get(0).message()).contains("'Create users'");
        verify(userService, never()).createUser(any(), anyString(), any(), any());
        verify(userService, never()).updateUser(any(), anyString(), any());
    }

    @Test
    void preview_reportsPermissionErrorsInTheRowMessage() throws IOException {
        BulkImportPreviewResult preview = service.previewImport(
                new ByteArrayInputStream("uid,cn,userPassword\nnewbie,New,\nexisting,Changed,pw\n"
                        .getBytes(StandardCharsets.UTF_8)),
                PARENT, "uid", List.of(), true, List.of(), null, ',', true, null,
                true, perms(false, true, Map.of("userpassword", BulkImportPermissions.RESET_PASSWORDS)),
                dn -> EXISTING.equals(dn));

        assertThat(preview.rows().get(0).message()).isEqualTo("Creating this user needs the 'Create users' permission");
        assertThat(preview.rows().get(1).message()).contains("userPassword").contains("'Reset user passwords'");
    }

    @Test
    void preview_withAllPermissions_hasNoMessages() throws IOException {
        BulkImportPreviewResult preview = service.previewImport(
                new ByteArrayInputStream("uid,cn\nnewbie,New\n".getBytes(StandardCharsets.UTF_8)),
                PARENT, "uid", List.of(), true, List.of(), null, ',', true, null,
                true, BulkImportPermissions.ALL, dn -> { throw new AssertionError("no lookup expected"); });

        assertThat(preview.rows().get(0).message()).isNull();
    }
}
