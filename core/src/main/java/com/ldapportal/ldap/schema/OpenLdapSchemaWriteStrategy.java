// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.schema;

import com.ldapportal.entity.enums.DirectoryType;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * OpenLDAP ({@code slapd} / OLC) schema-write mechanics. Schema is a set of
 * {@code olcSchemaConfig} entries under {@code cn=schema,cn=config}; new
 * elements are added as {@code olcAttributeTypes} / {@code olcObjectClasses}
 * values (or a new schema entry). The {@code cn=config} backend is written with
 * the config-admin bind, not the data bind, and only <em>adding</em> elements is
 * supported at runtime — modifying or removing an existing element requires
 * offline {@code slapd.d} editing.
 */
@Component
public class OpenLdapSchemaWriteStrategy implements SchemaWriteStrategy {

    private static final String CONTAINER = "cn=schema,cn=config";

    @Override
    public DirectoryType directoryType() {
        return DirectoryType.OPENLDAP;
    }

    @Override
    public Set<String> attributeTypeValueAttrs() {
        return Set.of("olcattributetypes");
    }

    @Override
    public Set<String> objectClassValueAttrs() {
        return Set.of("olcobjectclasses");
    }

    /**
     * Strips a leading {@code {n}} ordinal index that OpenLDAP prepends to
     * {@code olcAttributeTypes} / {@code olcObjectClasses} values in
     * {@code cn=config}, leaving a standard RFC 4512 definition.
     */
    @Override
    public String normalizeDefinition(String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String v = rawValue.stripLeading();
        if (v.startsWith("{")) {
            int close = v.indexOf('}');
            if (close > 0) {
                return v.substring(close + 1).stripLeading();
            }
        }
        return rawValue;
    }

    @Override
    public boolean isSchemaTargetDn(String dn) {
        return SchemaWriteStrategy.isWithin(dn, CONTAINER);
    }

    @Override
    public boolean requiresConfigConnection() {
        return true;
    }

    @Override
    public boolean supportsModifyExisting() {
        return false;
    }

    @Override
    public String schemaContainerDescription() {
        return CONTAINER;
    }
}
