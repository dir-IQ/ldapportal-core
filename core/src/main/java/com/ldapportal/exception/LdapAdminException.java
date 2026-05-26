// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.exception;

/**
 * Root of the LDAPPortal exception hierarchy.
 * All application-specific runtime exceptions extend this class so callers
 * can catch it generically or handle specific subtypes.
 */
public class LdapAdminException extends RuntimeException {

    public LdapAdminException(String message) {
        super(message);
    }

    public LdapAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
