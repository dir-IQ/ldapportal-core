// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync.identity;

import com.ldapportal.entity.enums.DirectoryType;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Identity via the operational {@code entryUUID} attribute, normalized to a
 * lowercase UUID string. Covers the OpenLDAP / 389 / OUD / OpenDJ family (and
 * the {@code GENERIC} fallback), all of which expose {@code entryUUID}.
 */
@Component
public class EntryUuidIdentityStrategy implements IdentityStrategy {

    /** A UUID in its canonical (lower-cased, as {@link #normalize} leaves it) form. */
    private static final Pattern UUID_FORM =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

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

    @Override
    public boolean isWellFormed(String normalized) {
        return normalized != null && UUID_FORM.matcher(normalized).matches();
    }
}
