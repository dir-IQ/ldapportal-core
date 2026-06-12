// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import java.util.List;

/**
 * 201 body for {@code POST /users}: the created entry plus the outcome
 * of the profile's server-side group assignments. {@code groupWarnings}
 * carries one message per group the user could NOT be added to — the
 * entry itself was still created, so this is a partial success the UI
 * must surface rather than a request failure. Empty (with
 * {@code groupsAdded = 0}) when no profile matched the target DN.
 */
public record UserCreateResponse(
        LdapEntryResponse entry,
        int groupsAdded,
        List<String> groupWarnings) {
}
