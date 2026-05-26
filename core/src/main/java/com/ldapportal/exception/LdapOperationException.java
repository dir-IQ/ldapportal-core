// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.exception;

/**
 * Thrown when an LDAP operation fails at the protocol level (e.g. search
 * returns an error result, modify is refused, DN not found).
 */
public class LdapOperationException extends LdapAdminException {

    public LdapOperationException(String message) {
        super(message);
    }

    public LdapOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
