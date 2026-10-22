// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.schema.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TargetEntryDifferTest {

    @Test
    void protectedAttrs_areNeverDeletedFromTarget() {
        Entry target = new Entry("uid=alice,ou=Users,dc=dst",
                new Attribute("uid", "alice"),
                new Attribute("cn", "Alice"),
                new Attribute("userPassword", "{SSHA}server-hash"),
                new Attribute("description", "stale"));
        // Desired keeps uid + cn. userPassword is excluded (protected); description
        // is no longer desired and should be removed.
        List<Attribute> desired = List.of(
                new Attribute("uid", "alice"),
                new Attribute("cn", "Alice"));

        List<Modification> mods =
                TargetEntryDiffer.diff(target, desired, Set.of("userpassword"));

        assertThat(mods).anySatisfy(m -> {
            assertThat(m.getModificationType()).isEqualTo(ModificationType.DELETE);
            assertThat(m.getAttributeName()).isEqualTo("description");
        });
        // The protected attribute is left untouched — never deleted.
        assertThat(mods).noneSatisfy(m ->
                assertThat(m.getAttributeName()).isEqualToIgnoringCase("userPassword"));
    }

    // ── comparing the way the directory does ────────────────────────────────

    private static Schema standardSchema() {
        try {
            return Schema.getDefaultStandardSchema();
        } catch (com.unboundid.ldap.sdk.LDAPException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void dnReference_differingOnlyInCaseAndSpacing_isNotAChange_evenWithoutASchema() {
        Entry target = new Entry("cn=sec,dc=dst",
                new Attribute("secDN", "cn=IVIA_Import_Test,o=Groups,c=us"));
        List<Attribute> desired = List.of(new Attribute("secDN", "CN=ivia_import_test, O=groups,C=US"));

        assertThat(TargetEntryDiffer.diff(target, desired, Set.of(), null, List.of("secDN"))).isEmpty();
        // The old exact comparison flagged it.
        assertThat(TargetEntryDiffer.diff(target, desired, Set.of())).hasSize(1);
    }

    @Test
    void dnReference_toAnotherEntry_isStillAChange() {
        Entry target = new Entry("cn=g,dc=dst", new Attribute("member", "uid=a,ou=People,dc=dst"));
        List<Attribute> desired = List.of(new Attribute("member", "uid=a,ou=People,dc=dst", "uid=b,ou=People,dc=dst"));

        List<Modification> mods = TargetEntryDiffer.diff(target, desired, Set.of(), null, List.of("member"));

        assertThat(mods).singleElement().satisfies(m -> {
            assertThat(m.getModificationType()).isEqualTo(ModificationType.REPLACE);
            assertThat(m.getValues()).containsExactlyInAnyOrder("uid=a,ou=People,dc=dst", "uid=b,ou=People,dc=dst");
        });
    }

    @Test
    void aCaseOnlyEdit_toANonDnAttribute_stillReplicates() {
        // cn matches case-ignore, but fixing "john smith" to "John Smith" at the
        // source is an edit the target should get.
        Entry target = new Entry("cn=a,dc=dst", new Attribute("cn", "john smith"));
        List<Attribute> desired = List.of(new Attribute("cn", "John Smith"));

        assertThat(TargetEntryDiffer.diff(target, desired, Set.of(), standardSchema(), List.of())).hasSize(1);
    }

    @Test
    void schemaDnSyntax_isComparedAsADn_withoutBeingAReferenceAttribute() {
        Entry target = new Entry("cn=a,dc=dst", new Attribute("seeAlso", "cn=X,dc=dst"));
        List<Attribute> desired = List.of(new Attribute("seeAlso", "CN=x, DC=dst"));

        assertThat(TargetEntryDiffer.diff(target, desired, Set.of(), standardSchema(), List.of())).isEmpty();
    }

    @Test
    void anAliasMatchesItsPrimaryName_soNothingIsReplacedOrDeleted() {
        // A server schema names sn's alias (UnboundID's bundled standard schema doesn't).
        Schema withAlias = new Schema(new Entry("cn=schema", new Attribute("attributeTypes",
                "( 2.5.4.4 NAME ( 'sn' 'surname' ) EQUALITY caseIgnoreMatch "
                        + "SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )")));
        Entry target = new Entry("cn=a,dc=dst", new Attribute("sn", "Smith"));
        List<Attribute> desired = List.of(new Attribute("surname", "Smith"));

        assertThat(TargetEntryDiffer.diff(target, desired, Set.of(), withAlias, List.of())).isEmpty();
    }

    @Test
    void attributeOptionsStayDistinct() {
        Entry target = new Entry("cn=a,dc=dst", new Attribute("cn", "A"), new Attribute("cn;lang-fr", "A"));
        List<Attribute> desired = List.of(new Attribute("cn", "A"));

        List<Modification> mods = TargetEntryDiffer.diff(target, desired, Set.of(), standardSchema(), List.of());

        assertThat(mods).singleElement().satisfies(m -> {
            assertThat(m.getModificationType()).isEqualTo(ModificationType.DELETE);
            assertThat(m.getAttributeName()).isEqualTo("cn;lang-fr");
        });
    }

    @Test
    void anAttributeTheSchemaDoesntKnow_isComparedExactly() {
        Entry target = new Entry("cn=a,dc=dst", new Attribute("secAuthority", "Default"));
        List<Attribute> desired = List.of(new Attribute("secAuthority", "default"));

        assertThat(TargetEntryDiffer.diff(target, desired, Set.of(), standardSchema(), List.of())).hasSize(1);
    }

    @Test
    void aReferenceValueThatIsntADn_fallsBackToAnExactComparison() {
        Entry target = new Entry("cn=a,dc=dst", new Attribute("owner", "not a dn"));

        assertThat(TargetEntryDiffer.diff(target, List.of(new Attribute("owner", "not a dn")), Set.of(), null,
                List.of("owner"))).isEmpty();
        assertThat(TargetEntryDiffer.diff(target, List.of(new Attribute("owner", "NOT A DN")), Set.of(), null,
                List.of("owner"))).hasSize(1);
    }
}
