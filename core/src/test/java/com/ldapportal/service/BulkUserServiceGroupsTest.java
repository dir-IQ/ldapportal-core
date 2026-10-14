// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.BulkImportPreviewResult;
import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.BulkImportRowResult;
import com.ldapportal.dto.csv.CsvColumnMappingDto;
import com.ldapportal.dto.csv.GroupMemberChange;
import com.ldapportal.dto.csv.GroupMemberChange.Outcome;
import com.ldapportal.dto.ldap.MembershipChangeRequest;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.entity.enums.ImportErrorHandling;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapUserService;
import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The user import's group-memberships column ({@code groups}, or a column mapped to {@code @groups}). */
@ExtendWith(MockitoExtension.class)
class BulkUserServiceGroupsTest {

    private static final String PARENT = "ou=people,dc=example,dc=com";
    private static final String ADMINS = "cn=admins,ou=groups,dc=example,dc=com";
    private static final String SUPPORT = "cn=Support,ou=groups,dc=example,dc=com";
    private static final String POSIX = "cn=devs,ou=groups,dc=example,dc=com";

    @Mock private LdapUserService userService;

    private BulkUserService service;
    private DirectoryConnection dc;
    private FakeGroups groups;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ProvisioningProfileService> none = mock(ObjectProvider.class);
        lenient().when(none.getIfAvailable()).thenReturn(null);
        service = new BulkUserService(userService, none);
        dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setBaseDn("dc=example,dc=com");
        groups = new FakeGroups();
    }

    /** Resolves a fixed set of groups and records the adds it is asked for. */
    static class FakeGroups implements UserImportGroups {
        boolean permitted = true;
        final Map<String, Target> byValue = new HashMap<>();
        final List<String> addedFor = new ArrayList<>();
        final List<List<MembershipChangeRequest.Change>> adds = new ArrayList<>();
        MembershipChangeResult.Status statusFor = MembershipChangeResult.Status.APPLIED;

        FakeGroups() {
            byValue.put("admins", Target.of(ADMINS, "member"));
            byValue.put(ADMINS.toLowerCase(Locale.ROOT), Target.of(ADMINS, "member"));
            byValue.put(SUPPORT.toLowerCase(Locale.ROOT), Target.of(SUPPORT, "uniqueMember"));
            byValue.put("devs", Target.of(POSIX, "memberUid"));
            byValue.put("engineering", Target.problem(Outcome.AMBIGUOUS, "More than one group has cn=engineering; use the group's DN"));
        }

        @Override public boolean mayManageMembers() { return permitted; }

        @Override public Target resolve(String value) {
            return byValue.getOrDefault(value.toLowerCase(Locale.ROOT),
                    Target.problem(Outcome.MEMBER_NOT_FOUND, "No group with cn=" + value));
        }

        @Override public MembershipChangeResult add(String userDn, String userUid,
                                                    List<MembershipChangeRequest.Change> changes) {
            addedFor.add(userDn + "|" + userUid);
            adds.add(changes);
            List<MembershipChangeResult.Item> items = changes.stream()
                    .map(c -> new MembershipChangeResult.Item(c.groupDn(), c.op(), statusFor,
                            statusFor == MembershipChangeResult.Status.QUEUED_FOR_APPROVAL ? UUID.randomUUID() : null,
                            statusFor == MembershipChangeResult.Status.BLOCKED ? "SoD rule" : null))
                    .toList();
            return MembershipChangeResult.of(items);
        }
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private BulkImportPreviewResult preview(String content, List<CsvColumnMappingDto> mappings,
                                            boolean passthrough) throws IOException {
        return service.previewImport(csv(content), PARENT, "uid", mappings, true, List.of(), null,
                ',', passthrough, groups);
    }

    private BulkImportResult importCsv(String content, ConflictHandling conflict) throws IOException {
        return service.importCsv(dc, csv(content), PARENT, "uid", conflict, List.of(), List.of(), true,
                null, null, List.of(), ImportErrorHandling.SKIP_ERRORS, ',', true, groups);
    }

    private static List<Outcome> outcomes(List<GroupMemberChange> changes) {
        return changes.stream().map(GroupMemberChange::outcome).toList();
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    @Test
    void preview_groupsHeader_isMembershipsNotAnAttribute_andEachGroupIsResolved() throws IOException {
        var result = preview("uid,cn,groups\n"
                + "jsmith,John,\"admins|" + SUPPORT + "|ghost|Admins|engineering\"\n", List.of(), true);

        var row = result.rows().get(0);
        assertThat(row.attributes()).containsOnlyKeys("uid", "cn");
        // "Admins" repeats "admins" (ignoring case) and is listed once.
        assertThat(row.memberChanges()).extracting(GroupMemberChange::member)
                .containsExactly("admins", SUPPORT, "ghost", "engineering");
        assertThat(outcomes(row.memberChanges())).containsExactly(
                Outcome.WILL_ADD, Outcome.WILL_ADD, Outcome.MEMBER_NOT_FOUND, Outcome.AMBIGUOUS);
        assertThat(row.memberChanges().get(0).memberDn()).isEqualTo(ADMINS);
        assertThat(row.memberChanges().get(2).message()).isEqualTo("No group with cn=ghost");
        // Group problems never count as missing data on the row.
        assertThat(row.missingRequired()).isEmpty();
        assertThat(groups.adds).isEmpty();
    }

    @Test
    void preview_aColumnMappedToGroupMemberships_underATemplate() throws IOException {
        var mappings = List.of(
                new CsvColumnMappingDto("login", "uid", false),
                new CsvColumnMappingDto("Teams", "@groups", false));
        var result = preview("login,Teams\njsmith,admins\n", mappings, false);

        var row = result.rows().get(0);
        assertThat(row.attributes()).containsOnlyKeys("uid");
        assertThat(outcomes(row.memberChanges())).containsExactly(Outcome.WILL_ADD);
        assertThat(result.unmappedColumns()).isEmpty();
    }

    @Test
    void preview_underATemplate_anUnmappedGroupsColumnIsJustUnmapped() throws IOException {
        var mappings = List.of(new CsvColumnMappingDto("uid", "uid", false));
        var result = preview("uid,groups\njsmith,admins\n", mappings, false);

        assertThat(result.rows().get(0).memberChanges()).isEmpty();
        assertThat(result.unmappedColumns()).containsExactly("groups");
    }

    @Test
    void preview_withoutPermission_refusesEveryGroup() throws IOException {
        groups.permitted = false;
        var result = preview("uid,groups\njsmith,admins|ghost\n", List.of(), true);

        assertThat(outcomes(result.rows().get(0).memberChanges()))
                .containsExactly(Outcome.REFUSED, Outcome.REFUSED);
        assertThat(result.rows().get(0).memberChanges().get(0).message())
                .isEqualTo("You don't have permission to manage group members");
    }

    @Test
    void preview_sameGroupByCnAndDn_isADuplicate_andMemberUidNeedsAUid() throws IOException {
        var result = preview("cn,groups\nJohn,\"admins|" + ADMINS + "|devs\"\n", List.of(), true);

        assertThat(outcomes(result.rows().get(0).memberChanges()))
                .containsExactly(Outcome.WILL_ADD, Outcome.DUPLICATE, Outcome.INVALID);
    }

    @Test
    void preview_withoutGroupsColumn_hasNoGroupChanges() throws IOException {
        var result = preview("uid,cn\njsmith,John\n", List.of(), true);
        assertThat(result.rows().get(0).memberChanges()).isEmpty();
    }

    // ── Import ────────────────────────────────────────────────────────────────

    @Test
    void import_createdUser_isAddedToResolvableGroups_andProblemsAreReported() throws IOException {
        BulkImportResult result = importCsv(
                "uid,cn,groups\njsmith,John,\"admins|" + SUPPORT + "|ghost\"\n", ConflictHandling.SKIP);

        BulkImportRowResult row = result.rows().get(0);
        // Partial failure: the user is still created.
        assertThat(row.status()).isEqualTo(BulkImportRowResult.Status.CREATED);
        assertThat(outcomes(row.memberChanges()))
                .containsExactly(Outcome.APPLIED, Outcome.APPLIED, Outcome.MEMBER_NOT_FOUND);

        assertThat(groups.addedFor).containsExactly("uid=jsmith," + PARENT + "|jsmith");
        assertThat(groups.adds.get(0)).containsExactly(
                new MembershipChangeRequest.Change(ADMINS, "member", MembershipChangeRequest.Op.ADD),
                new MembershipChangeRequest.Change(SUPPORT, "uniqueMember", MembershipChangeRequest.Op.ADD));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, List<String>>> attrs = ArgumentCaptor.forClass(Map.class);
        verify(userService).createUser(eq(dc), eq("uid=jsmith," + PARENT), attrs.capture(), any());
        assertThat(attrs.getValue()).doesNotContainKey("groups");
    }

    @Test
    void import_queuedAndBlockedAdds_keepTheirOutcome() throws IOException {
        groups.statusFor = MembershipChangeResult.Status.QUEUED_FOR_APPROVAL;
        var queued = importCsv("uid,groups\njsmith,admins\n", ConflictHandling.SKIP).rows().get(0);
        assertThat(outcomes(queued.memberChanges())).containsExactly(Outcome.QUEUED_FOR_APPROVAL);
        assertThat(queued.memberChanges().get(0).approvalId()).isNotNull();

        groups.statusFor = MembershipChangeResult.Status.BLOCKED;
        var blocked = importCsv("uid,groups\nadoe,admins\n", ConflictHandling.SKIP).rows().get(0);
        assertThat(outcomes(blocked.memberChanges())).containsExactly(Outcome.BLOCKED);
        assertThat(blocked.memberChanges().get(0).message()).isEqualTo("SoD rule");
    }

    @Test
    void import_skippedExistingUser_leavesGroupsAlone() throws IOException {
        doThrow(new LdapOperationException("createUser failed: " + ResultCode.ENTRY_ALREADY_EXISTS.getName()))
                .when(userService).createUser(eq(dc), anyString(), any(), any());

        var row = importCsv("uid,groups\njsmith,admins\n", ConflictHandling.SKIP).rows().get(0);

        assertThat(row.status()).isEqualTo(BulkImportRowResult.Status.SKIPPED);
        assertThat(row.message()).endsWith("; groups not changed");
        assertThat(row.memberChanges()).isEmpty();
        assertThat(groups.adds).isEmpty();
    }

    @Test
    void import_overwrittenUser_isAddedToGroups() throws IOException {
        doThrow(new LdapOperationException("createUser failed: " + ResultCode.ENTRY_ALREADY_EXISTS.getName()))
                .when(userService).createUser(eq(dc), anyString(), any(), any());

        var row = importCsv("uid,cn,groups\njsmith,John,admins\n", ConflictHandling.OVERWRITE).rows().get(0);

        assertThat(row.status()).isEqualTo(BulkImportRowResult.Status.UPDATED);
        assertThat(outcomes(row.memberChanges())).containsExactly(Outcome.APPLIED);
        // The groups column is never written as a user attribute.
        verify(userService).updateUser(eq(dc), eq("uid=jsmith," + PARENT),
                org.mockito.ArgumentMatchers.argThat(mods -> mods.stream()
                        .noneMatch(m -> m.getAttributeName().equalsIgnoreCase("groups"))));
    }

    @Test
    void import_withoutPermission_createsTheUserAndRefusesTheGroups() throws IOException {
        groups.permitted = false;
        var row = importCsv("uid,groups\njsmith,admins\n", ConflictHandling.SKIP).rows().get(0);

        assertThat(row.status()).isEqualTo(BulkImportRowResult.Status.CREATED);
        assertThat(outcomes(row.memberChanges())).containsExactly(Outcome.REFUSED);
        assertThat(groups.adds).isEmpty();
    }

    @Test
    void import_whenTheMembershipWriteThrows_eachPlannedAddIsAnError() throws IOException {
        UserImportGroups failing = new FakeGroups() {
            @Override public MembershipChangeResult add(String userDn, String userUid,
                                                        List<MembershipChangeRequest.Change> changes) {
                throw new IllegalStateException("directory unavailable");
            }
        };
        var row = service.importCsv(dc, csv("uid,groups\njsmith,admins|ghost\n"), PARENT, "uid",
                ConflictHandling.SKIP, List.of(), List.of(), true, null, null, List.of(),
                ImportErrorHandling.SKIP_ERRORS, ',', true, failing).rows().get(0);

        assertThat(row.status()).isEqualTo(BulkImportRowResult.Status.CREATED);
        assertThat(outcomes(row.memberChanges())).containsExactly(Outcome.ERROR, Outcome.MEMBER_NOT_FOUND);
        assertThat(row.memberChanges().get(0).message()).isEqualTo("directory unavailable");
    }
}
