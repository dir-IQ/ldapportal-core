// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.superadmin;

import java.util.List;

/**
 * Replace a superadmin account's granted permission set.
 *
 * @param permissions dot-notation permission dbValues to grant (the full
 *                    desired set; anything omitted is revoked)
 */
public record UpdateSuperadminPermissionsRequest(List<String> permissions) {
}
