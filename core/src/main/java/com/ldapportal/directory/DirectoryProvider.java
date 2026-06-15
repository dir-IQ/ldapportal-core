// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.directory;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Abstraction over directory backends (LDAP, Entra ID, etc.).
 * Each implementation handles one {@link DirectoryType}.
 */
public interface DirectoryProvider {

    /** Which directory types this provider handles. */
    List<DirectoryType> supportedTypes();

    /**
     * Search for users matching a filter expression.
     * Filter semantics are provider-specific (LDAP filter syntax vs OData $filter).
     */
    List<DirectoryUser> searchUsers(DirectoryConnection dc, String filter, int maxResults);

    /** Get a single user by identifier (DN for LDAP, object ID for Entra). */
    DirectoryUser getUser(DirectoryConnection dc, String identifier);

    /** Search for groups matching a filter expression. */
    List<DirectoryGroup> searchGroups(DirectoryConnection dc, String filter, int maxResults);

    /** Get member identifiers for a group. */
    List<String> getGroupMembers(DirectoryConnection dc, String groupId);

    /** Poll audit events since a given timestamp. */
    List<DirectoryAuditEvent> pollAuditEvents(DirectoryConnection dc, OffsetDateTime since);

    /** Test connectivity to the directory. Returns null on success, error message on failure. */
    String testConnection(DirectoryConnection dc);
}
