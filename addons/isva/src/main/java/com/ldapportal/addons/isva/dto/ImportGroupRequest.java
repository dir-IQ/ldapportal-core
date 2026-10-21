// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional IVIA group name for an import; blank uses the group's {@code cn}.
 * IVIA group names are unique within the group container.
 */
public record ImportGroupRequest(@Size(max = 256) String name) {
}
