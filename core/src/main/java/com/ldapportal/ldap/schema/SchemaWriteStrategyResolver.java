// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.schema;

import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.exception.LdapOperationException;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the {@link SchemaWriteStrategy} for a directory type, or rejects the
 * directory when schema-via-LDIF is not supported for it (Active Directory,
 * IBM Directory Server, generic, and Entra ID — which has no LDAP schema).
 */
@Component
public class SchemaWriteStrategyResolver {

    private final Map<DirectoryType, SchemaWriteStrategy> byType = new EnumMap<>(DirectoryType.class);

    public SchemaWriteStrategyResolver(List<SchemaWriteStrategy> strategies) {
        for (SchemaWriteStrategy s : strategies) {
            byType.put(s.directoryType(), s);
        }
    }

    /**
     * @throws LdapOperationException (→ 422) when the directory type has no
     *         supported schema-write strategy
     */
    public SchemaWriteStrategy resolve(DirectoryType type) {
        SchemaWriteStrategy s = byType.get(type);
        if (s == null) {
            throw new LdapOperationException(
                    "Schema updates via LDIF are not supported for directory type "
                            + type + ". Supported: " + byType.keySet() + ".");
        }
        return s;
    }

    /** Whether a strategy exists for the given type (without throwing). */
    public boolean supports(DirectoryType type) {
        return byType.containsKey(type);
    }
}
