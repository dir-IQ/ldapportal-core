// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.ldap.LdapSchemaService.AttributeTypeInfo;
import com.ldapportal.ldap.LdapSchemaService.ObjectClassDetail;
import com.ldapportal.ldap.LdapSchemaService.SchemaListItem;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The browse-list mapping: sorted, and de-duplicated by name. Some directories
 * (notably OUD) return the same schema element more than once — an objectClass
 * whose definition resolves to the same first NAME appears multiple times — and
 * duplicate names would both clutter the list and break the browser's
 * name-keyed reconciliation (a stale, un-filterable list). Built against a
 * hand-rolled schema carrying deliberate duplicates.
 */
class LdapSchemaServiceListTest {

    private static Schema schemaWithDuplicates() throws LDAPException {
        Entry e = new Entry("cn=schema",
            new Attribute("objectClass", "top", "ldapSubentry", "subschema"),
            new Attribute("cn", "schema"),
            new Attribute("objectClasses",
                "( 1.2.3.1 NAME 'dupClass' SUP top STRUCTURAL )",
                "( 1.2.3.2 NAME 'dupClass' SUP top STRUCTURAL )",
                "( 1.2.3.3 NAME 'dupClass' SUP top STRUCTURAL )",
                "( 1.2.3.4 NAME 'aClass' SUP top STRUCTURAL )"),
            new Attribute("attributeTypes",
                "( 4.3.2.1 NAME 'dupAttr' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )",
                "( 4.3.2.2 NAME 'dupAttr' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )",
                "( 4.3.2.3 NAME 'zAttr' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )"));
        return new Schema(e);
    }

    @Test
    void objectClassItems_dedupesByName_andSortsCaseInsensitively() throws LDAPException {
        List<SchemaListItem> items = LdapSchemaService.objectClassItems(schemaWithDuplicates());

        assertThat(items).extracting(SchemaListItem::name)
            .containsExactly("aClass", "dupClass");   // three dupClass collapse to one
        // The kept dupClass is the first after sorting (OID 1.2.3.1).
        assertThat(items).filteredOn(i -> i.name().equals("dupClass"))
            .singleElement()
            .extracting(SchemaListItem::oid).isEqualTo("1.2.3.1");
    }

    @Test
    void attributeTypeItems_dedupesByName() throws LDAPException {
        List<AttributeTypeInfo> items = LdapSchemaService.attributeTypeItems(schemaWithDuplicates());

        assertThat(items).extracting(AttributeTypeInfo::name)
            .containsExactly("dupAttr", "zAttr");
    }

    // ── inheritance hierarchy ──────────────────────────────────────────

    /**
     * A small tree plus a multiple-inheritance (diamond) case:
     * <pre>
     *   top ← base ← middle ← leaf
     *                middle ← sibling
     *   top ← aux
     *   leaf, aux ← multi        (two SUP classes)
     * </pre>
     */
    private static Schema hierarchySchema() throws LDAPException {
        Entry e = new Entry("cn=schema",
            new Attribute("objectClass", "top", "ldapSubentry", "subschema"),
            new Attribute("cn", "schema"),
            new Attribute("objectClasses",
                "( 2.1 NAME 'base' SUP top STRUCTURAL MUST baseAttr )",
                "( 2.2 NAME 'middle' SUP base STRUCTURAL MAY midAttr )",
                "( 2.3 NAME 'leaf' SUP middle STRUCTURAL )",
                "( 2.4 NAME 'sibling' SUP middle STRUCTURAL )",
                "( 2.5 NAME 'aux' SUP top AUXILIARY )",
                "( 2.6 NAME 'multi' SUP ( leaf $ aux ) STRUCTURAL )",
                "( 2.7 NAME 'orphan' STRUCTURAL )"),
            new Attribute("attributeTypes",
                "( 3.1 NAME 'baseAttr' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )",
                "( 3.2 NAME 'midAttr' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15 )"));
        return new Schema(e);
    }

    private static ObjectClassDetail detailFor(Schema schema, String name) {
        return LdapSchemaService.buildObjectClassDetail(schema, schema.getObjectClass(name));
    }

    @Test
    void detail_reportsAncestorChain_nearestParentFirst() throws LDAPException {
        Schema schema = hierarchySchema();

        assertThat(detailFor(schema, "leaf").superiors())
            .containsExactly("middle", "base", "top");
    }

    @Test
    void detail_reportsDirectSubclassesOnly_sorted() throws LDAPException {
        Schema schema = hierarchySchema();

        // middle's direct children — not the grandchild 'multi' under leaf.
        assertThat(detailFor(schema, "middle").subclasses())
            .containsExactly("leaf", "sibling");
        assertThat(detailFor(schema, "leaf").subclasses()).containsExactly("multi");
        assertThat(detailFor(schema, "multi").subclasses()).isEmpty();
    }

    @Test
    void detail_handlesMultipleInheritance_withoutDuplicatingSharedAncestors() throws LDAPException {
        Schema schema = hierarchySchema();

        // multi SUP (leaf $ aux) — both branches reach top, which must appear once.
        List<String> superiors = detailFor(schema, "multi").superiors();
        assertThat(superiors).contains("leaf", "aux", "middle", "base", "top");
        assertThat(superiors).doesNotHaveDuplicates();
        // Nearest parents come before the ancestors they pull in.
        assertThat(superiors.indexOf("leaf")).isLessThan(superiors.indexOf("middle"));
    }

    @Test
    void detail_reportsClassKind_andCarriesAttributeSets() throws LDAPException {
        Schema schema = hierarchySchema();

        assertThat(detailFor(schema, "aux").kind()).isEqualTo("AUXILIARY");
        ObjectClassDetail leaf = detailFor(schema, "leaf");
        assertThat(leaf.kind()).isEqualTo("STRUCTURAL");
        // Same inherited attribute sets the pre-existing endpoint returned.
        assertThat(leaf.required()).contains("baseAttr");
        assertThat(leaf.optional()).contains("midAttr");
    }

    @Test
    void detail_classWithoutSup_hasNoSuperiors() throws LDAPException {
        Schema schema = hierarchySchema();
        assertThat(detailFor(schema, "orphan").superiors()).isEmpty();
        assertThat(detailFor(schema, "orphan").subclasses()).isEmpty();
    }

    @Test
    void detail_reportsUnresolvableSuperiorByName() throws LDAPException {
        // 'top' is referenced as a SUP but not defined in this fixture — a
        // dangling SUP is still reported rather than silently dropped.
        assertThat(detailFor(hierarchySchema(), "base").superiors()).containsExactly("top");
    }
}
