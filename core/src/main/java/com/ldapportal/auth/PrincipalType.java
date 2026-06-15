// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

/**
 * Distinguishes the two account types that can authenticate against the API.
 */
public enum PrincipalType {
    /** Platform-level superadmin — full access to all realms and system settings. */
    SUPERADMIN,
    /** Realm-scoped admin — subject to the four-dimensional permission model. */
    ADMIN,
    /** End user authenticated via LDAP bind-as-self for self-service portal. */
    SELF_SERVICE
}
