// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import java.util.UUID;

/**
 * Immutable principal placed in {@link org.springframework.security.core.context.SecurityContextHolder}
 * after successful JWT validation.
 */
public record AuthPrincipal(
        PrincipalType type,
        UUID id,
        String username,
        /** User's full LDAP DN — non-null only for {@link PrincipalType#SELF_SERVICE}. */
        String dn,
        /** Directory the user authenticated against — non-null only for {@link PrincipalType#SELF_SERVICE}. */
        UUID directoryId) {

    /** Convenience constructor for admin principals (no dn/directoryId). */
    public AuthPrincipal(PrincipalType type, UUID id, String username) {
        this(type, id, username, null, null);
    }

    public boolean isSuperadmin() {
        return type == PrincipalType.SUPERADMIN;
    }

    public boolean isSelfService() {
        return type == PrincipalType.SELF_SERVICE;
    }
}
