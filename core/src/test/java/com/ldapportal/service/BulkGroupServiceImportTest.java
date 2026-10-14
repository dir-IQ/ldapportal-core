// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.BulkImportResult;
import com.ldapportal.dto.csv.BulkImportRowResult;
import com.ldapportal.dto.csv.BulkImportRowResult.Status;
import com.ldapportal.dto.csv.GroupMemberChange;
import com.ldapportal.dto.csv.GroupMemberChange.Outcome;
import com.ldapportal.dto.ldap.MembershipChangeRequest.Op;
import com.ldapportal.dto.ldap.MembershipChangeResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.ldap.LdapGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BulkGroupService#importCsv}: groups are created with all their
 * resolved members; changes to existing groups go through the governed path
 * ({@link GroupImportMembers#apply}) one at a time; the plan (resolution,
 * no-ops, duplicates, conflicts) is fixed before anything is written.
 */
@ExtendWith(MockitoExtension.class)
class BulkGroupServiceImportTest {

    @Mock private LdapGroupService groupService;

    private BulkGroupService service;
    private DirectoryConnection dc;
    private FakeGroupImportMembers dir;

    private static final String PARENT = "ou=groups,dc=example,dc=com";
    private static final String ENG = "cn=engineering," + PARENT;
    private static final String ALICE = "uid=alice,ou=people,dc=example,dc=com";
    private static final String BOB = "uid=bob,ou=people,dc=example,dc=com";
    private static final String CAROL = "uid=carol,ou=people,dc=example,dc=com";

    @BeforeEach
    void setUp() {
        service = new BulkGroupService(groupService);
        dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setBaseDn("dc=example,dc=com");
        dir = new FakeGroupImportMembers()
                .user("alice", ALICE).user("bob", BOB).user("carol", CAROL);
    }

    private BulkImportResult run(String csv) throws IOException {
        return run(csv, ConflictHandling.SKIP, "member");
    }

    private BulkImportResult run(String csv, ConflictHandling conflict, String memberAttr) throws IOException {
        return service.importCsv(dc, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), PARENT,
                conflict, List.of(), List.of("groupOfNames"), memberAttr, true, ',', true, dir);
    }

    private static List<Outcome> outcomes(BulkImportRowResult row) {
        return row.memberChanges().stream().map(GroupMemberChange::outcome).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> createdAttrs(String dn) {
        ArgumentCaptor<Map<String, List<String>>> attrs = ArgumentCaptor.forClass(Map.class);
        verify(groupService).createGroup(eq(dc), eq(dn), attrs.capture());
        return attrs.getValue();
    }

    // ── New groups ────────────────────────────────────────────────────────────

    @Test
    void newGroup_isCreatedWithAllResolvedMembers_uidsAndDnsAlike() throws IOException {
        BulkImportResult result = run("cn,description,members\nengineering,Eng team,\"alice|" + BOB + "\"\n");

        Map<String, List<String>> attrs = createdAttrs(ENG);
        assertThat(attrs).containsEntry("member", List.of(ALICE, BOB))
                .containsEntry("description", List.of("Eng team"))
                .containsEntry("objectClass", List.of("groupOfNames"));
        assertThat(dir.writes).isEmpty(); // members went in the create, not the governed path
        BulkImportRowResult row = result.rows().get(0);
        assertThat(row.status()).isEqualTo(Status.CREATED);
        assertThat(outcomes(row)).containsExactly(Outcome.APPLIED, Outcome.APPLIED);
        assertThat(row.memberChanges().get(0).memberDn()).isEqualTo(ALICE);
        assertThat(row.message()).isEqualTo("Group created; 2 added");
    }

    @Test
    void newGroup_withAnUnresolvableMember_isCreatedWithTheRestAndReportsTheProblem() throws IOException {
        dir.problem("ghost", Outcome.MEMBER_NOT_FOUND);

        BulkImportResult result = run("cn,members\nengineering,alice|ghost\n");

        assertThat(createdAttrs(ENG)).containsEntry("member", List.of(ALICE));
        BulkImportRowResult row = result.rows().get(0);
        assertThat(row.status()).isEqualTo(Status.ERROR);
        assertThat(outcomes(row)).containsExactly(Outcome.APPLIED, Outcome.MEMBER_NOT_FOUND);
        assertThat(row.message()).isEqualTo("Group created; 1 added; 1 not found");
    }

    @Test
    void newPosixGroup_getsUidsInMemberUid() throws IOException {
        run("cn,members\nengineering,\"alice|" + BOB + "\"\n", ConflictHandling.SKIP, "memberUid");

        assertThat(createdAttrs(ENG)).containsEntry("memberUid", List.of("alice", "bob"));
    }

    @Test
    void laterRowForAGroupAnEarlierRowCreates_updatesIt() throws IOException {
        BulkImportResult result = run("cn,members\nengineering,alice\nengineering,bob\n");

        verify(groupService).createGroup(eq(dc), eq(ENG), any());
        assertThat(dir.writes).containsExactly(
                new FakeGroupImportMembers.Write(BOB, "bob", ENG, "member", Op.ADD));
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
    }

    // ── Existing groups: governed member writes ───────────────────────────────

    @Test
    void existingGroup_addsOnlyMissingMembers_throughTheGovernedPath() throws IOException {
        dir.group(ENG, "member", ALICE);

        BulkImportResult result = run("cn,members\nengineering,alice|bob\n");

        verify(groupService, never()).createGroup(any(), anyString(), any());
        assertThat(dir.writes).containsExactly(
                new FakeGroupImportMembers.Write(BOB, "bob", ENG, "member", Op.ADD));
        BulkImportRowResult row = result.rows().get(0);
        assertThat(row.status()).isEqualTo(Status.UPDATED);
        assertThat(outcomes(row)).containsExactly(Outcome.ALREADY_MEMBER, Outcome.APPLIED);
        assertThat(row.message()).isEqualTo("1 added; 1 already a member");
    }

    @Test
    void existingGroup_writesToTheAttributeTheGroupUses() throws IOException {
        dir.group(ENG, "uniqueMember", ALICE);

        run("cn,members\nengineering,bob\n");

        assertThat(dir.writes).extracting(FakeGroupImportMembers.Write::memberAttribute)
                .containsExactly("uniqueMember");
    }

    @Test
    void existingGroup_noMembersToAdd_isSkipped() throws IOException {
        dir.group(ENG, "member", ALICE);

        BulkImportResult result = run("cn,description\nengineering,Eng team\n");

        assertThat(dir.writes).isEmpty();
        verify(groupService, never()).updateGroup(any(), anyString(), any());
        assertThat(result.rows().get(0).status()).isEqualTo(Status.SKIPPED);
        assertThat(result.rows().get(0).message()).isEqualTo("Group already exists (no members to add)");
    }

    @Test
    void existingGroup_overwriteReplacesOtherAttributes_andStillAddsMembers() throws IOException {
        dir.group(ENG, "member", ALICE);

        BulkImportResult result = run("cn,description,members\nengineering,New desc,bob\n",
                ConflictHandling.OVERWRITE, "member");

        verify(groupService).updateGroup(eq(dc), eq(ENG), any());
        assertThat(dir.writes).hasSize(1);
        assertThat(result.rows().get(0).status()).isEqualTo(Status.UPDATED);
        assertThat(result.rows().get(0).message()).isEqualTo("Attributes replaced; 1 added");
    }

    @Test
    void governedOutcomes_areReportedPerMember() throws IOException {
        dir.group(ENG, "member", "uid=zed,ou=people,dc=example,dc=com");
        dir.outcome = w -> switch (w.memberUid()) {
            case "alice" -> MembershipChangeResult.Status.QUEUED_FOR_APPROVAL;
            case "bob" -> MembershipChangeResult.Status.BLOCKED;
            default -> MembershipChangeResult.Status.REFUSED;
        };

        BulkImportResult result = run("cn,members\nengineering,alice|bob|carol\n");

        BulkImportRowResult row = result.rows().get(0);
        assertThat(outcomes(row)).containsExactly(
                Outcome.QUEUED_FOR_APPROVAL, Outcome.BLOCKED, Outcome.REFUSED);
        assertThat(row.memberChanges().get(0).approvalId()).isEqualTo(FakeGroupImportMembers.APPROVAL_ID);
        assertThat(row.memberChanges().get(1).message()).isEqualTo("SoD violation");
        assertThat(row.status()).isEqualTo(Status.ERROR);
        assertThat(row.message()).isEqualTo("1 queued for approval; 1 refused; 1 blocked by separation of duties");
    }

    @Test
    void queuedOnly_countsAsUpdated() throws IOException {
        dir.group(ENG, "member", ALICE);
        dir.outcome = w -> MembershipChangeResult.Status.QUEUED_FOR_APPROVAL;

        BulkImportResult result = run("cn,members\nengineering,bob\n");

        assertThat(result.rows().get(0).status()).isEqualTo(Status.UPDATED);
    }

    // ── member-operation: delete ──────────────────────────────────────────────

    @Test
    void delete_removesOnlyCurrentMembers_throughTheGovernedPath() throws IOException {
        dir.group(ENG, "member", ALICE, BOB);

        BulkImportResult result = run("cn,members,member-operation\nengineering,alice|carol,DELETE\n");

        verify(groupService, never()).createGroup(any(), anyString(), any());
        assertThat(dir.writes).containsExactly(
                new FakeGroupImportMembers.Write(ALICE, "alice", ENG, "member", Op.REMOVE));
        BulkImportRowResult row = result.rows().get(0);
        assertThat(row.status()).isEqualTo(Status.UPDATED);
        assertThat(outcomes(row)).containsExactly(Outcome.APPLIED, Outcome.NOT_A_MEMBER);
        assertThat(row.message()).isEqualTo("1 removed; 1 not a member");
    }

    @Test
    void delete_noneAreMembers_isSkipped() throws IOException {
        dir.group(ENG, "member", ALICE);

        BulkImportResult result = run("cn,members,member-operation\nengineering,bob,delete\n");

        assertThat(dir.writes).isEmpty();
        assertThat(result.rows().get(0).status()).isEqualTo(Status.SKIPPED);
    }

    @Test
    void delete_onAMissingGroup_isAnErrorAndNeverCreates() throws IOException {
        BulkImportResult result = run("cn,members,member-operation\nengineering,alice,delete\n");

        verify(groupService, never()).createGroup(any(), anyString(), any());
        assertThat(result.rows().get(0).status()).isEqualTo(Status.ERROR);
        assertThat(result.rows().get(0).message()).isEqualTo("Group does not exist");
    }

    @Test
    void delete_ignoresOverwrite() throws IOException {
        dir.group(ENG, "member", ALICE, BOB);

        run("cn,description,members,member-operation\nengineering,New desc,alice,delete\n",
                ConflictHandling.OVERWRITE, "member");

        verify(groupService, never()).updateGroup(any(), anyString(), any());
    }

    @Test
    void invalidMemberOperation_isAnError() throws IOException {
        BulkImportResult result = run("cn,members,member-operation\nengineering,alice,replace\n");

        verify(groupService, never()).createGroup(any(), anyString(), any());
        assertThat(result.rows().get(0).status()).isEqualTo(Status.ERROR);
        assertThat(result.rows().get(0).message())
                .isEqualTo("Invalid member-operation 'replace': expected 'add' or 'delete'");
    }

    // ── Plan checks across the file ───────────────────────────────────────────

    @Test
    void sameMembershipTwice_secondIsADuplicate() throws IOException {
        dir.group(ENG, "member", ALICE);

        BulkImportResult result = run("cn,members\nengineering,bob\nengineering,bob\n");

        assertThat(dir.writes).hasSize(1);
        assertThat(outcomes(result.rows().get(1))).containsExactly(Outcome.DUPLICATE);
        assertThat(result.rows().get(1).memberChanges().get(0).message()).isEqualTo("Repeats row 1");
        assertThat(result.rows().get(1).status()).isEqualTo(Status.SKIPPED);
    }

    @Test
    void addAndRemoveOfTheSameMembership_neitherRuns() throws IOException {
        dir.group(ENG, "member", ALICE);

        BulkImportResult result = run("cn,members,member-operation\nengineering,bob,add\nengineering,alice|bob,delete\n");

        // Only alice's removal is uncontested.
        assertThat(dir.writes).containsExactly(
                new FakeGroupImportMembers.Write(ALICE, "alice", ENG, "member", Op.REMOVE));
        assertThat(outcomes(result.rows().get(0))).containsExactly(Outcome.INVALID);
        assertThat(outcomes(result.rows().get(1))).containsExactly(Outcome.APPLIED, Outcome.INVALID);
        assertThat(result.rows().get(0).memberChanges().get(0).message())
                .isEqualTo("The file both adds and removes this membership");
    }

    @Test
    void memberUidGroup_withAMemberThatHasNoUid_isInvalid() throws IOException {
        String nested = "cn=nested,ou=groups,dc=example,dc=com";
        dir.group(ENG, "memberUid", "zed").entry(nested);

        BulkImportResult result = run("cn,members\nengineering,\"" + nested + "\"\n");

        assertThat(dir.writes).isEmpty();
        assertThat(outcomes(result.rows().get(0))).containsExactly(Outcome.INVALID);
        assertThat(result.rows().get(0).memberChanges().get(0).message())
                .isEqualTo("Group uses memberUid but this member has no uid");
    }

    @Test
    void tooManyMemberChanges_rejectsTheWholeFile() {
        StringBuilder csv = new StringBuilder("cn,members\n");
        StringBuilder members = new StringBuilder();
        for (int i = 0; i <= BulkGroupService.MAX_MEMBER_CHANGES; i++) {
            if (i > 0) members.append('|');
            members.append("u").append(i);
        }
        csv.append("engineering,").append(members).append('\n');

        assertThatThrownBy(() -> run(csv.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("split the file");
        verify(groupService, never()).createGroup(any(), anyString(), any());
    }

    // ── Columns ───────────────────────────────────────────────────────────────

    @Test
    void exportedDnColumn_isIgnoredNotWrittenAsAttribute() throws IOException {
        BulkImportResult result = run("dn,cn,description,member\n\"" + ENG + "\",engineering,Eng team,alice\n");

        assertThat(createdAttrs(ENG)).doesNotContainKey("dn")
                .containsEntry("description", List.of("Eng team"))
                .containsEntry("member", List.of(ALICE));
        assertThat(result.created()).isEqualTo(1);
    }

    @Test
    void excelByteOrderMark_onTheFirstHeader_isIgnored() throws IOException {
        BulkImportResult result = run("﻿cn,members\nengineering,alice\n");

        verify(groupService).createGroup(eq(dc), eq(ENG), any());
        assertThat(result.created()).isEqualTo(1);
    }

    @Test
    void memberOperationColumn_isNeverWrittenAsAnAttribute() throws IOException {
        run("cn,members,member-operation\nengineering,alice,add\n");

        assertThat(createdAttrs(ENG)).doesNotContainKey("member-operation");
    }
}
