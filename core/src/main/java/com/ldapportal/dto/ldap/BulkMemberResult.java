// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;

/**
 * Result of a bulk member addition to an LDAP group.
 */
public record BulkMemberResult(
        int added,
        int failed,
        List<BulkMemberError> errors) {

    public record BulkMemberError(String memberValue, String error) {}
}
