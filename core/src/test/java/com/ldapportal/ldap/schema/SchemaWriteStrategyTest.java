// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.schema;

import com.ldapportal.entity.enums.DirectoryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the vendor schema-write strategies: the security boundary
 * ({@code isSchemaTargetDn}), the OpenLDAP {@code {n}} prefix normalization, and
 * the per-vendor capability flags.
 */
class SchemaWriteStrategyTest {

    private final OpenLdapSchemaWriteStrategy openldap = new OpenLdapSchemaWriteStrategy();
    private final OpenDjSchemaWriteStrategy opendj = new OpenDjSchemaWriteStrategy();

    @Test
    void openldap_targets_and_flags() {
        assertThat(openldap.directoryType()).isEqualTo(DirectoryType.OPENLDAP);
        assertThat(openldap.requiresConfigConnection()).isTrue();
        assertThat(openldap.supportsModifyExisting()).isFalse();
        assertThat(openldap.attributeTypeValueAttrs()).contains("olcattributetypes");
        assertThat(openldap.objectClassValueAttrs()).contains("olcobjectclasses");
    }

    @Test
    void openldap_scope_allows_schema_container_and_children_only() {
        assertThat(openldap.isSchemaTargetDn("cn=schema,cn=config")).isTrue();
        assertThat(openldap.isSchemaTargetDn("cn=isva-test,cn=schema,cn=config")).isTrue();
        assertThat(openldap.isSchemaTargetDn("cn={0}core,cn=schema,cn=config")).isTrue();
        // Anything outside the schema container is rejected — the security boundary.
        assertThat(openldap.isSchemaTargetDn("cn=config")).isFalse();
        assertThat(openldap.isSchemaTargetDn("ou=people,dc=openldap,dc=example,dc=com")).isFalse();
        assertThat(openldap.isSchemaTargetDn("cn=schema")).isFalse();
        assertThat(openldap.isSchemaTargetDn(null)).isFalse();
    }

    @Test
    void openldap_normalizes_olc_ordinal_prefix() {
        assertThat(openldap.normalizeDefinition("{0}( 1.2.3 NAME 'x' )"))
                .isEqualTo("( 1.2.3 NAME 'x' )");
        assertThat(openldap.normalizeDefinition("  {12}( 1.2.3 )"))
                .isEqualTo("( 1.2.3 )");
        // No prefix — passed through unchanged.
        assertThat(openldap.normalizeDefinition("( 1.2.3 NAME 'x' )"))
                .isEqualTo("( 1.2.3 NAME 'x' )");
    }

    @Test
    void opendj_targets_and_flags() {
        assertThat(opendj.directoryType()).isEqualTo(DirectoryType.ORACLE_UNIFIED_DIRECTORY);
        assertThat(opendj.requiresConfigConnection()).isFalse();
        assertThat(opendj.supportsModifyExisting()).isTrue();
        assertThat(opendj.attributeTypeValueAttrs()).contains("attributetypes");
        assertThat(opendj.objectClassValueAttrs()).contains("objectclasses");
        assertThat(opendj.normalizeDefinition("( 1.2.3 )")).isEqualTo("( 1.2.3 )");
    }

    @Test
    void opendj_scope_allows_only_cn_schema() {
        assertThat(opendj.isSchemaTargetDn("cn=schema")).isTrue();
        assertThat(opendj.isSchemaTargetDn("cn=schema,cn=config")).isFalse();
        assertThat(opendj.isSchemaTargetDn("ou=people,dc=example,dc=com")).isFalse();
    }
}
