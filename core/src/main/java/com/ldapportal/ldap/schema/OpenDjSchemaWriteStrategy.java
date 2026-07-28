// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.schema;

import com.ldapportal.entity.enums.DirectoryType;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * OpenDJ / Oracle Unified Directory schema-write mechanics. Schema lives in the
 * {@code cn=schema} subentry as {@code attributeTypes} / {@code objectClasses}
 * values, written with the directory's normal bind. Unlike OpenLDAP, existing
 * elements may be modified online.
 *
 * <p>The local-dev fixture uses OpenDJ (the Apache-2.0 community fork of the
 * ODSEE codebase Oracle forked to build OUD), which is why the community build
 * can test this path end-to-end. Both map to
 * {@link DirectoryType#ORACLE_UNIFIED_DIRECTORY}.</p>
 */
@Component
public class OpenDjSchemaWriteStrategy implements SchemaWriteStrategy {

    private static final String CONTAINER = "cn=schema";

    @Override
    public DirectoryType directoryType() {
        return DirectoryType.ORACLE_UNIFIED_DIRECTORY;
    }

    @Override
    public Set<String> attributeTypeValueAttrs() {
        return Set.of("attributetypes");
    }

    @Override
    public Set<String> objectClassValueAttrs() {
        return Set.of("objectclasses");
    }

    @Override
    public boolean isSchemaTargetDn(String dn) {
        // The subschema subentry is exactly cn=schema on OpenDJ/OUD.
        return SchemaWriteStrategy.isWithin(dn, CONTAINER);
    }

    @Override
    public boolean requiresConfigConnection() {
        return false;
    }

    @Override
    public boolean supportsModifyExisting() {
        return true;
    }

    @Override
    public String schemaContainerDescription() {
        return CONTAINER;
    }
}
