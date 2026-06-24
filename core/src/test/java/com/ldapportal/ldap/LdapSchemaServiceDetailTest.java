// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.ldap.LdapSchemaService.AttributeTypeDetail;
import com.ldapportal.ldap.LdapSchemaService.AttributeUsage;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.schema.AttributeTypeDefinition;
import com.unboundid.ldap.sdk.schema.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reverse usage index + syntax resolution, exercised against UnboundID's
 * bundled standard schema (RFC 4519/4517) so the assertions ride on real,
 * well-known definitions: {@code cn SUP name}, {@code person MUST cn},
 * {@code organizationalPerson SUP person}, etc.
 */
class LdapSchemaServiceDetailTest {

    private static Schema schema;

    @BeforeAll
    static void loadSchema() throws LDAPException {
        schema = Schema.getDefaultStandardSchema();
    }

    private AttributeTypeDetail detailFor(String attr) {
        return LdapSchemaService.buildAttributeDetail(schema, schema.getAttributeType(attr));
    }

    private Map<String, AttributeUsage> usageByClass(AttributeTypeDetail d) {
        return d.usedBy().stream().collect(Collectors.toMap(
            u -> u.objectClass().toLowerCase(), Function.identity(), (a, b) -> a));
    }

    @Test
    void cn_usage_distinguishes_direct_must_from_inherited() {
        Map<String, AttributeUsage> usage = usageByClass(detailFor("cn"));

        // person declares cn as a required (MUST) attribute directly.
        assertThat(usage).containsKey("person");
        assertThat(usage.get("person").required()).isTrue();
        assertThat(usage.get("person").inherited()).isFalse();

        // organizationalPerson / inetOrgPerson pick cn up through SUP person.
        assertThat(usage.get("organizationalperson").required()).isTrue();
        assertThat(usage.get("organizationalperson").inherited()).isTrue();
        assertThat(usage.get("inetorgperson").required()).isTrue();
        assertThat(usage.get("inetorgperson").inherited()).isTrue();
    }

    @Test
    void usage_is_sorted_case_insensitively_by_object_class() {
        var names = detailFor("cn").usedBy().stream().map(AttributeUsage::objectClass).toList();
        assertThat(names).isEqualTo(names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList());
    }

    @Test
    void optional_usage_is_flagged_as_may() {
        // 'description' is an optional (MAY) attribute on its classes.
        AttributeTypeDetail d = detailFor("description");
        assertThat(d.usedBy()).isNotEmpty();
        assertThat(d.usedBy()).anyMatch(u -> !u.required());
    }

    @Test
    void syntax_resolves_through_the_superior_type_chain() {
        // cn declares no syntax of its own — it inherits SUP name's Directory
        // String. Resolution must follow the SUP chain and name the OID.
        var syntax = detailFor("cn").syntax();
        assertThat(syntax).isNotNull();
        assertThat(syntax.oid()).isEqualTo("1.3.6.1.4.1.1466.115.121.1.15");
        assertThat(syntax.description()).containsIgnoringCase("Directory String");
    }

    @Test
    void syntax_length_hint_is_parsed_when_the_server_publishes_one() throws LDAPException {
        // Many servers append a {len} bound to the syntax OID; UnboundID keeps
        // it on getSyntaxOID(), so we split it into a base OID + maxLength.
        AttributeTypeDefinition atd = new AttributeTypeDefinition(
            "( 1.2.3.4.5 NAME 'testLenAttr' SYNTAX 1.3.6.1.4.1.1466.115.121.1.15{128} )");
        var syntax = LdapSchemaService.buildAttributeDetail(schema, atd).syntax();
        assertThat(syntax.oid()).isEqualTo("1.3.6.1.4.1.1466.115.121.1.15");
        assertThat(syntax.maxLength()).isEqualTo(128);
        assertThat(syntax.description()).isEqualTo("Directory String");
    }

    @Test
    void catalog_names_well_known_syntaxes_and_is_null_for_others() {
        assertThat(LdapSyntaxCatalog.describe("1.3.6.1.4.1.1466.115.121.1.15")).isEqualTo("Directory String");
        assertThat(LdapSyntaxCatalog.describe("1.3.6.1.4.1.1466.115.121.1.7")).isEqualTo("Boolean");
        assertThat(LdapSyntaxCatalog.describe("1.3.6.1.4.1.1466.115.121.1.27")).isEqualTo("Integer");
        assertThat(LdapSyntaxCatalog.describe("9.9.9.9")).isNull();
        assertThat(LdapSyntaxCatalog.describe(null)).isNull();
    }
}
