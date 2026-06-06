// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

import com.ldapportal.entity.enums.LdapChangeOp;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.SearchResultEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Edge-case coverage for {@link OudChangelogChangeParser} — the highest-risk,
 * format-sensitive piece of changelog capture (design §5). Pins the
 * reconstructed {@code rawPayload} shape so it stays byte-identical to the
 * {@code ReplicationEnqueuer} live-capture path.
 */
class OudChangelogChangeParserTest {

    // ── add ───────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void add_basicAttributes() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "add"),
                new Attribute("targetDN", "uid=alice,ou=people,dc=test"),
                new Attribute("changes",
                        "objectClass: inetOrgPerson\nuid: alice\ncn: Alice\nsn: Smith\n"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();

        assertThat(change.operation()).isEqualTo(LdapChangeOp.ADD);
        assertThat(change.sourceDn()).isEqualTo("uid=alice,ou=people,dc=test");
        Map<String, List<String>> attrs =
                (Map<String, List<String>>) change.rawPayload().get("attributes");
        assertThat(attrs).containsEntry("uid", List.of("alice"));
        assertThat(attrs).containsEntry("cn", List.of("Alice"));
        assertThat(attrs.get("objectClass")).containsExactly("inetOrgPerson");
    }

    @Test
    @SuppressWarnings("unchecked")
    void add_multiValuedAttribute() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "add"),
                new Attribute("targetDN", "cn=admins,ou=groups,dc=test"),
                new Attribute("changes",
                        "objectClass: groupOfNames\n"
                                + "member: uid=a,dc=test\n"
                                + "member: uid=b,dc=test\n"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();
        Map<String, List<String>> attrs =
                (Map<String, List<String>>) change.rawPayload().get("attributes");
        assertThat(attrs.get("member")).containsExactly("uid=a,dc=test", "uid=b,dc=test");
    }

    @Test
    @SuppressWarnings("unchecked")
    void add_base64EncodedValue_isDecoded() {
        // "Y2Fmw6k=" is base64 of "café" (UTF-8). LDIF "::" marks a base64 value.
        SearchResultEntry entry = entry(
                new Attribute("changeType", "add"),
                new Attribute("targetDN", "uid=cafe,dc=test"),
                new Attribute("changes", "uid: cafe\ndescription:: Y2Fmw6k=\n"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();
        Map<String, List<String>> attrs =
                (Map<String, List<String>>) change.rawPayload().get("attributes");
        assertThat(attrs.get("description")).containsExactly("café");
    }

    @Test
    @SuppressWarnings("unchecked")
    void add_foldedContinuationLine_isUnfolded() {
        // A line beginning with a single space continues the previous one.
        SearchResultEntry entry = entry(
                new Attribute("changeType", "add"),
                new Attribute("targetDN", "uid=fold,dc=test"),
                new Attribute("changes", "uid: fold\ncn: abc\n def\n"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();
        Map<String, List<String>> attrs =
                (Map<String, List<String>>) change.rawPayload().get("attributes");
        assertThat(attrs.get("cn")).containsExactly("abcdef");
    }

    @Test
    @SuppressWarnings("unchecked")
    void add_attributeNameWithBinaryOption_preserved() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "add"),
                new Attribute("targetDN", "uid=cert,dc=test"),
                new Attribute("changes", "uid: cert\nuserCertificate;binary:: Y2Fmw6k=\n"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();
        Map<String, List<String>> attrs =
                (Map<String, List<String>>) change.rawPayload().get("attributes");
        assertThat(attrs).containsKey("userCertificate;binary");
    }

    @Test
    void add_emptyChanges_throwsParseException() {
        // An attribute-less add can't be a valid write; dead-letter it rather
        // than emit a broken operation.
        SearchResultEntry entry = entry(
                new Attribute("changeType", "add"),
                new Attribute("targetDN", "uid=empty,dc=test"),
                new Attribute("changes", "   "));

        assertThatThrownBy(() -> OudChangelogChangeParser.parse(entry))
                .isInstanceOf(ChangelogParseException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void add_leadingBlankLine_isTolerated() {
        // A stray leading newline must not dead-letter an otherwise-valid add.
        SearchResultEntry entry = entry(
                new Attribute("changeType", "add"),
                new Attribute("targetDN", "uid=alice,dc=test"),
                new Attribute("changes", "\nuid: alice\ncn: Alice\n"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();
        Map<String, List<String>> attrs =
                (Map<String, List<String>>) change.rawPayload().get("attributes");
        assertThat(attrs).containsEntry("uid", List.of("alice"));
        assertThat(attrs).containsEntry("cn", List.of("Alice"));
    }

    // ── modify ─────────────────────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void modify_multipleModifications() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "modify"),
                new Attribute("targetDN", "uid=bob,dc=test"),
                new Attribute("changes",
                        "add: description\ndescription: hello\n-\n"
                                + "replace: mail\nmail: a@b.com\nmail: c@d.com\n-"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();
        assertThat(change.operation()).isEqualTo(LdapChangeOp.MODIFY);

        List<Map<String, Object>> mods =
                (List<Map<String, Object>>) change.rawPayload().get("modifications");
        assertThat(mods).hasSize(2);
        assertThat(mods.get(0)).containsEntry("type", "ADD")
                .containsEntry("name", "description")
                .containsEntry("values", List.of("hello"));
        assertThat(mods.get(1)).containsEntry("type", "REPLACE")
                .containsEntry("name", "mail")
                .containsEntry("values", List.of("a@b.com", "c@d.com"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void modify_deleteWholeAttribute_hasEmptyValues() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "modify"),
                new Attribute("targetDN", "uid=bob,dc=test"),
                new Attribute("changes", "delete: member\n-"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();
        List<Map<String, Object>> mods =
                (List<Map<String, Object>>) change.rawPayload().get("modifications");
        assertThat(mods).hasSize(1);
        assertThat(mods.get(0)).containsEntry("type", "DELETE").containsEntry("name", "member");
        assertThat((List<String>) mods.get(0).get("values")).isEmpty();
    }

    @Test
    void modify_emptyChanges_throwsParseException() {
        // A modify with zero modifications is a no-op the server rejects;
        // dead-letter it rather than emit it.
        SearchResultEntry entry = entry(
                new Attribute("changeType", "modify"),
                new Attribute("targetDN", "uid=bob,dc=test"),
                new Attribute("changes", ""));

        assertThatThrownBy(() -> OudChangelogChangeParser.parse(entry))
                .isInstanceOf(ChangelogParseException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void modify_strayBlankLineBetweenGroups_isTolerated() {
        // Well-formed modify groups (each terminated by '-') with a stray blank
        // line between them must still parse — the blank isn't a record
        // separator in our single-record decode.
        SearchResultEntry entry = entry(
                new Attribute("changeType", "modify"),
                new Attribute("targetDN", "uid=bob,dc=test"),
                new Attribute("changes",
                        "add: description\ndescription: hello\n-\n\nreplace: mail\nmail: x@y.com\n-"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();
        List<Map<String, Object>> mods =
                (List<Map<String, Object>>) change.rawPayload().get("modifications");
        assertThat(mods).hasSize(2);
        assertThat(mods.get(0)).containsEntry("name", "description");
        assertThat(mods.get(1)).containsEntry("name", "mail");
    }

    @Test
    void modify_malformedChanges_throwsParseException() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "modify"),
                new Attribute("targetDN", "uid=bob,dc=test"),
                new Attribute("changes", "this line has no colon and is not valid ldif"));

        assertThatThrownBy(() -> OudChangelogChangeParser.parse(entry))
                .isInstanceOf(ChangelogParseException.class);
    }

    // ── delete ─────────────────────────────────────────────────────────────────

    @Test
    void delete_emptyPayload() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "delete"),
                new Attribute("targetDN", "uid=gone,dc=test"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();
        assertThat(change.operation()).isEqualTo(LdapChangeOp.DELETE);
        assertThat(change.sourceDn()).isEqualTo("uid=gone,dc=test");
        assertThat(change.rawPayload()).isEmpty();
    }

    // ── modrdn ─────────────────────────────────────────────────────────────────

    @Test
    void modrdn_withNewSuperior_isMove() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "modrdn"),
                new Attribute("targetDN", "uid=jane,ou=old,dc=test"),
                new Attribute("newRDN", "uid=jane"),
                new Attribute("deleteOldRDN", "TRUE"),
                new Attribute("newSuperior", "ou=new,dc=test"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();
        assertThat(change.operation()).isEqualTo(LdapChangeOp.MODIFY_DN);
        assertThat(change.rawPayload()).containsEntry("newRdn", "uid=jane")
                .containsEntry("deleteOldRdn", true)
                .containsEntry("newSuperiorDn", "ou=new,dc=test");
    }

    @Test
    void modrdn_missingNewRdn_throwsParseException() {
        // A rename/move with no newRDN is meaningless; dead-letter it.
        SearchResultEntry entry = entry(
                new Attribute("changeType", "modrdn"),
                new Attribute("targetDN", "uid=jane,ou=people,dc=test"),
                new Attribute("deleteOldRDN", "TRUE"));

        assertThatThrownBy(() -> OudChangelogChangeParser.parse(entry))
                .isInstanceOf(ChangelogParseException.class);
    }

    @Test
    void modrdn_withoutNewSuperior_isRename_nullSuperior() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "modrdn"),
                new Attribute("targetDN", "uid=jane,ou=people,dc=test"),
                new Attribute("newRDN", "uid=janet"),
                new Attribute("deleteOldRDN", "FALSE"));

        ChangelogChange change = OudChangelogChangeParser.parse(entry).orElseThrow();
        assertThat(change.rawPayload()).containsEntry("newRdn", "uid=janet")
                .containsEntry("deleteOldRdn", false);
        assertThat(change.rawPayload().get("newSuperiorDn")).isNull();
    }

    // ── non-recordable / unrecognised ──────────────────────────────────────────

    @Test
    void missingTargetDn_returnsEmpty() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "add"),
                new Attribute("changes", "uid: x\n"));

        assertThat(OudChangelogChangeParser.parse(entry)).isEmpty();
    }

    @Test
    void unknownChangeType_returnsEmpty() {
        SearchResultEntry entry = entry(
                new Attribute("changeType", "bind"),
                new Attribute("targetDN", "uid=x,dc=test"));

        Optional<ChangelogChange> change = OudChangelogChangeParser.parse(entry);
        assertThat(change).isEmpty();
    }

    // ── helper ─────────────────────────────────────────────────────────────────

    private static SearchResultEntry entry(Attribute... attrs) {
        return new SearchResultEntry("changeNumber=1,cn=changelog", attrs);
    }
}
