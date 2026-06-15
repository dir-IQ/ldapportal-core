// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.directory;

import java.util.List;
import java.util.Map;

/**
 * Directory-agnostic user representation.
 * Works for both LDAP entries and Entra ID user objects.
 */
public record DirectoryUser(
        /** DN for LDAP, object ID for Entra ID. */
        String id,
        String displayName,
        /** uid for LDAP, userPrincipalName for Entra ID. */
        String loginName,
        String email,
        boolean enabled,
        Map<String, List<String>> attributes,
        List<String> groupIds
) {}
