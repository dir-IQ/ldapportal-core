// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.exception;

/**
 * Thrown when an operation would violate a uniqueness constraint
 * or refuses on a domain rule. Maps to HTTP 409 in the REST layer.
 *
 * <p>The optional {@code code} is surfaced as a {@code code} property on
 * the RFC 7807 ProblemDetail body so frontends can dispatch on a stable
 * symbol instead of parsing the message. Existing callers that pass only
 * a message see no change in behaviour — {@code code} stays null.</p>
 */
public class ConflictException extends LdapAdminException {

    private final String code;

    public ConflictException(String message) {
        this(null, message);
    }

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
