// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.exception;

/**
 * Thrown when a connection to an LDAP server cannot be established or
 * maintained (e.g. network error, TLS handshake failure, bind failure).
 */
public class LdapConnectionException extends LdapAdminException {

    public LdapConnectionException(String message) {
        super(message);
    }

    public LdapConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
