// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.ldap.LdapSchemaService.AttributeTypeInfo;
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
}
