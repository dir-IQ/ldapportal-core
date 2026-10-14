// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.BulkImportPreviewResult;
import com.ldapportal.dto.csv.BulkImportPreviewRow;
import com.ldapportal.dto.csv.BulkImportPreviewRow.GroupAction;
import com.ldapportal.dto.csv.CsvColumnMappingDto;
import com.ldapportal.dto.csv.GroupAttributeChange;
import com.ldapportal.dto.csv.GroupMemberChange;
import com.ldapportal.dto.csv.GroupMemberChange.Outcome;
import com.ldapportal.ldap.LdapGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link BulkGroupService#previewImport}: the same plan the import carries
 * out, with nothing written — what each row does to its group, the planned
 * outcome of every member change, and required-attribute checks for rows
 * that create a group.
 */
@ExtendWith(MockitoExtension.class)
class BulkGroupServicePreviewTest {

    @Mock private LdapGroupService groupService;

    private BulkGroupService service;
    private FakeGroupImportMembers dir;

    private static final String PARENT = "ou=groups,dc=example,dc=com";
    private static final String ENG = "cn=eng," + PARENT;
    private static final String ALICE = "uid=alice,ou=people,dc=example,dc=com";
    private static final String BOB = "uid=bob,ou=people,dc=example,dc=com";

    @BeforeEach
    void setUp() {
        service = new BulkGroupService(groupService);
        dir = new FakeGroupImportMembers().user("alice", ALICE).user("bob", BOB);
    }

    private BulkImportPreviewResult preview(String csv, List<String> required, String memberAttr) throws IOException {
        return preview(csv, List.of(), required, memberAttr, true);
    }

    private BulkImportPreviewResult preview(String csv, List<CsvColumnMappingDto> mappings, List<String> required,
                                            String memberAttr, boolean passthrough) throws IOException {
        return service.previewImport(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), PARENT,
                mappings, true, required, memberAttr, List.of("groupOfNames"), ',', passthrough, dir);
    }

    private static List<Outcome> outcomes(BulkImportPreviewRow row) {
        return row.memberChanges().stream().map(GroupMemberChange::outcome).toList();
    }

    @Test
    void newGroup_isACreate_withEveryMemberPlannedAsAnAdd() throws IOException {
        BulkImportPreviewResult result = preview("cn,members,member-operation\neng,\"alice|" + BOB + "\",add\n", List.of("member"), "member");

        BulkImportPreviewRow row = result.rows().get(0);
        assertThat(row.groupAction()).isEqualTo(GroupAction.CREATE);
        assertThat(outcomes(row)).containsExactly(Outcome.WILL_ADD, Outcome.WILL_ADD);
        assertThat(row.memberChanges().get(0).memberDn()).isEqualTo(ALICE);
        assertThat(row.missingRequired()).isEmpty();
        verifyNoInteractions(groupService); // nothing written
        assertThat(dir.writes).isEmpty();
    }

    @Test
    void existingGroup_isAnUpdate_withNoOpsAndProblemsShown() throws IOException {
        dir.group(ENG, "member", ALICE).problem("ghost", Outcome.AMBIGUOUS);

        BulkImportPreviewRow row = preview("cn,members,member-operation\neng,alice|bob|ghost,add\n", List.of("member"), "member")
                .rows().get(0);

        assertThat(row.groupAction()).isEqualTo(GroupAction.UPDATE);
        assertThat(outcomes(row)).containsExactly(Outcome.ALREADY_MEMBER, Outcome.WILL_ADD, Outcome.AMBIGUOUS);
        // Updating an existing group doesn't need the object class's MUSTs.
        assertThat(row.missingRequired()).isEmpty();
    }

    @Test
    void existingGroup_listsOnlyTheProvidedAttributesThatChange() throws IOException {
        dir.group(ENG, "member", ALICE).attr(ENG, "description", "Old desc").attr(ENG, "owner", BOB);

        BulkImportPreviewResult result = preview(
                "cn,description,owner,members,member-operation\neng,New desc,,,\nnewgroup,Fresh,,alice,add\n", List.of(), "member");

        // owner is blank, so it's left alone; description changes.
        assertThat(result.rows().get(0).attributeChanges())
                .containsExactly(new GroupAttributeChange("description", "Old desc", "New desc"));
        // A group the file creates has nothing to compare against.
        assertThat(result.rows().get(1).groupAction()).isEqualTo(GroupAction.CREATE);
        assertThat(result.rows().get(1).attributeChanges()).isEmpty();
    }

    @Test
    void delete_plansRemovalsForCurrentMembersOnly() throws IOException {
        dir.group(ENG, "member", ALICE);

        BulkImportPreviewRow row = preview("cn,members,member-operation\neng,alice|bob,delete\n",
                List.of("member"), "member").rows().get(0);

        assertThat(row.groupAction()).isEqualTo(GroupAction.UPDATE);
        assertThat(outcomes(row)).containsExactly(Outcome.WILL_REMOVE, Outcome.NOT_A_MEMBER);
        assertThat(row.attributes()).containsEntry("member-operation", "delete");
        assertThat(row.missingRequired()).isEmpty();
    }

    @Test
    void delete_onAMissingGroup_explainsWhyTheRowCantRun() throws IOException {
        BulkImportPreviewRow row = preview("cn,members,member-operation\neng,alice,delete\n",
                List.of("member"), "member").rows().get(0);

        assertThat(row.groupAction()).isNull();
        assertThat(row.message()).isEqualTo("Group does not exist");
    }

    @Test
    void entryThatIsNotAGroup_explainsWhyTheRowCantRun() throws IOException {
        dir.nonGroup(ENG);

        BulkImportPreviewRow row = preview("cn,members,member-operation\neng,alice,add\n", List.of(), "member").rows().get(0);

        assertThat(row.message()).isEqualTo("The entry at " + ENG + " is not a group");
    }

    @Test
    void conflictingRows_areFlaggedInvalid() throws IOException {
        dir.group(ENG, "member", ALICE);

        BulkImportPreviewResult result = preview(
                "cn,members,member-operation\neng,bob,add\neng,bob,delete\n", List.of(), "member");

        assertThat(outcomes(result.rows().get(0))).containsExactly(Outcome.INVALID);
        assertThat(outcomes(result.rows().get(1))).containsExactly(Outcome.INVALID);
    }

    @Test
    void newGroupOfNames_withoutAResolvableMember_isFlaggedAndCantRun() throws IOException {
        dir.problem("ghost", Outcome.MEMBER_NOT_FOUND);

        BulkImportPreviewResult result = preview(
                "cn,members,member-operation\neng,ghost,add\nsolo,,\n", List.of("member"), "member");

        for (BulkImportPreviewRow row : result.rows()) {
            assertThat(row.missingRequired()).containsExactly("member");
            assertThat(row.message()).isEqualTo("A new groupOfNames group needs at least one member");
            assertThat(row.groupAction()).isNull();
        }
        assertThat(outcomes(result.rows().get(0))).containsExactly(Outcome.MEMBER_NOT_FOUND);
    }

    @Test
    void membersWithoutMemberOperation_isFlagged() throws IOException {
        dir.group(ENG, "member", ALICE);

        BulkImportPreviewRow row = preview("cn,members\neng,bob\n", List.of(), "member").rows().get(0);

        assertThat(row.missingRequired()).containsExactly("member-operation");
        assertThat(row.message())
                .isEqualTo("The member-operation column is required when members are listed: use 'add' or 'delete'");
    }

    @Test
    void uniqueMemberObjectClass_isSatisfiedByTheMembersColumn() throws IOException {
        BulkImportPreviewRow row = preview("cn,members,member-operation\neng,alice,add\n", List.of("uniqueMember"), "uniqueMember")
                .rows().get(0);

        assertThat(row.missingRequired()).isEmpty();
    }

    @Test
    void missingCn_isFlagged() throws IOException {
        BulkImportPreviewRow row = preview("cn,members,member-operation\n,alice,add\n", List.of("member"), "member").rows().get(0);

        assertThat(row.computedDn()).isNull();
        assertThat(row.missingRequired()).contains("cn");
    }

    @Test
    void invalidMemberOperation_isFlaggedButTheDnIsStillShown() throws IOException {
        BulkImportPreviewRow row = preview("cn,members,member-operation\neng,alice,replace\n", List.of(), "member")
                .rows().get(0);

        assertThat(row.computedDn()).isEqualTo(ENG);
        assertThat(row.missingRequired()).containsExactly("member-operation");
        assertThat(row.message()).isEqualTo("Invalid member-operation 'replace': expected 'add' or 'delete'");
    }

    @Test
    void exportedDnColumn_isIgnored() throws IOException {
        BulkImportPreviewRow row = preview("dn,cn,member,member-operation\n\"" + ENG + "\",eng,alice,add\n", List.of("member"), "member")
                .rows().get(0);

        assertThat(row.attributes()).doesNotContainKey("dn");
        assertThat(row.computedDn()).isEqualTo(ENG);
        assertThat(row.missingRequired()).isEmpty();
    }

    @Test
    void underTemplate_unmappedColumnsAreListed_butNotMemberOperation() throws IOException {
        var mappings = List.of(
                new CsvColumnMappingDto("name", "cn", false),
                new CsvColumnMappingDto("members", "members", false));

        BulkImportPreviewResult result = preview("name,members,member-operation,Owner\neng,alice,add,uid=boss\n",
                mappings, List.of(), "member", false);

        assertThat(result.unmappedColumns()).containsExactly("Owner");
        assertThat(result.rows().get(0).attributes()).containsKey("cn").doesNotContainKey("Owner")
                .containsEntry("member-operation", "add");
    }

    @Test
    void preview_withMembers_withoutManageMembers_isRefused() {
        dir.mayManageMembers = false;

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> preview("cn,members\neng,alice\n", List.of(), "member"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void withoutTemplate_headersPassThrough() throws IOException {
        BulkImportPreviewResult result = preview("cn,description\neng,Engineers\n", List.of(), "member");

        assertThat(result.unmappedColumns()).isEmpty();
        assertThat(result.rows().get(0).attributes()).containsEntry("description", "Engineers");
    }
}
