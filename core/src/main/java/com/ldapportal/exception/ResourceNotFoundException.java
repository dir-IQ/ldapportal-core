// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.exception;

/**
 * Thrown when a requested resource (LDAP entry, database record, etc.)
 * cannot be found.  Maps to HTTP 404 in the REST layer (Phase 3).
 */
public class ResourceNotFoundException extends LdapAdminException {

    public ResourceNotFoundException(String resourceType, Object id) {
        super(resourceType + " not found: " + id);
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
