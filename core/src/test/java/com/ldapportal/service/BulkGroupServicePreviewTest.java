// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.csv.BulkImportPreviewResult;
import com.ldapportal.ldap.LdapGroupService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for {@link BulkGroupService#previewImport} required-attribute
 * validation. The full import path is covered elsewhere.
 */
@ExtendWith(MockitoExtension.class)
class BulkGroupServicePreviewTest {

    @Mock private LdapGroupService groupService;

    private BulkGroupService service;

    @BeforeEach
    void setUp() {
        service = new BulkGroupService(groupService);
    }

    private InputStream csv(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void previewImport_membersColumnSatisfiesMemberAttrRequirement() throws IOException {
        // groupOfNames requires: cn + member. The CSV uses 'members' as the
        // column header (the bulk-group import's convention). Preview should
        // recognise that 'members' supplies the chosen memberAttr ('member')
        // — i.e. row 1 has members → not flagged; row 2 has no members → flagged.
        String csv = "cn,description,owner,members\n"
                   + "engineering,Eng team,owner1,uid=alice|uid=bob\n"
                   + "lonely,Solo group,owner2,\n";

        BulkImportPreviewResult result = service.previewImport(
                csv(csv),
                "ou=groups,dc=example,dc=com",
                List.of(),  // passthrough column mappings
                true,
                List.of("member"),
                "member");

        assertThat(result.totalRows()).isEqualTo(2);
        assertThat(result.rows().get(0).missingRequired()).isEmpty();
        assertThat(result.rows().get(1).missingRequired()).containsExactly("member");
    }

    @Test
    void previewImport_uniqueMemberObjectClass_alsoSatisfiedByMembersColumn() throws IOException {
        // groupOfUniqueNames requires uniqueMember. The CSV's 'members' column
        // gets translated to whatever memberAttr the caller specifies, so
        // populating 'members' should satisfy uniqueMember too.
        String csv = "cn,members\nteam-x,uid=alice\n";

        BulkImportPreviewResult result = service.previewImport(
                csv(csv),
                "ou=groups,dc=example,dc=com",
                List.of(), true,
                List.of("uniqueMember"),
                "uniqueMember");

        assertThat(result.rows().get(0).missingRequired()).isEmpty();
    }

    @Test
    void previewImport_legacyOverload_skipsValidation() throws IOException {
        // The 4-arg overload is the unchanged backwards-compat path.
        String csv = "cn\nteam-x\n";

        BulkImportPreviewResult result = service.previewImport(
                csv(csv),
                "ou=groups,dc=example,dc=com",
                List.of(), true);

        assertThat(result.rows().get(0).missingRequired()).isEmpty();
    }

    @Test
    void previewImport_missingCn_flaggedAsMissingRequired() throws IOException {
        // Row missing cn — the group RDN. Same treatment as missing schema
        // MUSTs so the row gets highlighted + counted at preview time.
        String csv = "cn,members\n,uid=alice|uid=bob\n";

        BulkImportPreviewResult result = service.previewImport(
                csv(csv),
                "ou=groups,dc=example,dc=com",
                List.of(), true,
                List.of("member"),
                "member");

        assertThat(result.rows().get(0).computedDn()).isNull();
        assertThat(result.rows().get(0).missingRequired()).contains("cn");
    }
}
