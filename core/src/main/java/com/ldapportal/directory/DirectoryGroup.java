// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.directory;

/**
 * Directory-agnostic group representation.
 */
public record DirectoryGroup(
        /** DN for LDAP, object ID for Entra ID. */
        String id,
        String name,
        String description,
        int memberCount
) {}
