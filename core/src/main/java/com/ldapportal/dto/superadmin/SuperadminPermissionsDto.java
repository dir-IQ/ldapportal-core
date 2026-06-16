// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.superadmin;

import java.util.List;

/**
 * A superadmin account's permission state, for the permission editor.
 *
 * @param all       the full catalogue of permission keys (dot-notation dbValues)
 * @param granted   the keys actually granted on this account (the editable set)
 * @param effective the effective keys (granted, expanded to {@code all} for owners)
 * @param owner     true when the account holds {@code MANAGE_SUPERADMINS}
 */
public record SuperadminPermissionsDto(
        List<String> all,
        List<String> granted,
        List<String> effective,
        boolean owner) {
}
