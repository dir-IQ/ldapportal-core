// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body for {@code POST /isva-account/revoke?dn=...}. The {@link IsvaRevokeMode}
 * discriminator picks SOFT (mark invalid + expired, audit USER_DISABLE) or
 * HARD (remove the account outright, audit USER_DELETE).
 */
public record RevokeRequest(@NotNull IsvaRevokeMode mode) {
}
