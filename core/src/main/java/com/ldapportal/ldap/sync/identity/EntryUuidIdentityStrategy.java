// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync.identity;

import com.ldapportal.entity.enums.DirectoryType;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Identity via the operational {@code entryUUID} attribute, normalized to a
 * lowercase UUID string. Covers the OpenLDAP / 389 / OUD / OpenDJ family (and
 * the {@code GENERIC} fallback), all of which expose {@code entryUUID}.
 */
@Component
public class EntryUuidIdentityStrategy implements IdentityStrategy {

    @Override
    public boolean supports(DirectoryType type) {
        return switch (type) {
            case OPENLDAP, ORACLE_UNIFIED_DIRECTORY, IBM_DIRECTORY_SERVER, GENERIC -> true;
            default -> false;
        };
    }

    @Override
    public String identityAttribute() {
        return "entryUUID";
    }

    @Override
    public String normalize(String rawValue) {
        return rawValue == null ? null : rawValue.trim().toLowerCase(Locale.ROOT);
    }
}
