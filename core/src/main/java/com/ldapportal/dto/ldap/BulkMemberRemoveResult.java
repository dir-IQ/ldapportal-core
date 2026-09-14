// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import com.ldapportal.dto.ldap.BulkMemberResult.BulkMemberError;

import java.util.List;

/**
 * Result of a bulk member removal from an LDAP group. Mirrors
 * {@link BulkMemberResult}; each value is attempted independently, so a
 * value that isn't currently a member fails on its own line without
 * aborting the rest.
 */
public record BulkMemberRemoveResult(
        int removed,
        int failed,
        List<BulkMemberError> errors) {
}
