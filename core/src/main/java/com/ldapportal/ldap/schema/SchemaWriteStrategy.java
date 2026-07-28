// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.schema;

import com.ldapportal.entity.enums.DirectoryType;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPException;

import java.util.Set;

/**
 * Per-vendor mechanics of applying schema changes via LDIF. Reading schema is
 * uniform ({@code conn.getSchema()}); <em>writing</em> it is not — the target
 * DN, the attribute that carries element definitions, and the bind identity all
 * differ between directory servers. Implementations are pure (no LDAP calls):
 * {@code SchemaLdifService} performs the actual writes on an approved
 * chokepoint.
 *
 * <ul>
 *   <li><b>OpenLDAP</b> — schema lives under {@code cn=schema,cn=config} as
 *       {@code olcAttributeTypes} / {@code olcObjectClasses}, written with the
 *       config-admin bind; additive-only online.</li>
 *   <li><b>OpenDJ / OUD</b> — schema lives in the {@code cn=schema} subentry as
 *       {@code attributeTypes} / {@code objectClasses}, written with the normal
 *       directory bind; existing elements may be modified online.</li>
 * </ul>
 */
public interface SchemaWriteStrategy {

    /** The directory type this strategy handles. */
    DirectoryType directoryType();

    /** Lower-cased attribute names whose values are attributeType definitions. */
    Set<String> attributeTypeValueAttrs();

    /** Lower-cased attribute names whose values are objectClass definitions. */
    Set<String> objectClassValueAttrs();

    /**
     * Normalizes a raw definition value before parsing. OpenLDAP's {@code cn=config}
     * form prefixes values with an ordinal index (e.g. {@code {0}( 1.3.6... )});
     * that prefix is stripped here so the value parses as a standard RFC 4512
     * definition.
     */
    default String normalizeDefinition(String rawValue) {
        return rawValue;
    }

    /**
     * The security boundary: whether {@code dn} is an allowed schema-write target
     * for this vendor. Records targeting anything else are rejected so this
     * endpoint cannot write arbitrary directory entries.
     */
    boolean isSchemaTargetDn(String dn);

    /** True when writes must go through a separate {@code cn=config} bind. */
    boolean requiresConfigConnection();

    /** True when existing schema elements can be modified/removed online. */
    boolean supportsModifyExisting();

    /**
     * True when all schema lives in a single, always-present subentry that is
     * updated with MODIFY (add: attributeTypes/objectClasses) rather than by
     * ADD-ing an entry. OpenDJ/OUD ({@code cn=schema}) return true; an uploaded
     * subschema dump (no {@code changetype} — the shape {@code ldapsearch} and
     * this app's own schema export produce) must therefore be applied as a
     * modify of that subentry, since adding it would fail with "entry already
     * exists". OpenLDAP returns false: its elements are ADD-ed as new
     * {@code olcSchemaConfig} child entries under {@code cn=schema,cn=config}.
     */
    default boolean writesToExistingContainer() {
        return false;
    }

    /** Human-readable schema container, used in messages. */
    String schemaContainerDescription();

    /** Helper: is {@code dn} equal to, or a descendant of, {@code containerDn}? */
    static boolean isWithin(String dn, String containerDn) {
        if (dn == null) {
            return false;
        }
        try {
            DN target = new DN(dn);
            DN container = new DN(containerDn);
            return target.equals(container) || target.isDescendantOf(container, false);
        } catch (LDAPException e) {
            return false;
        }
    }
}
