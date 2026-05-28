// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/**
 * Body for {@code POST /isva-account/renew?dn=...}. The service-side
 * forward-only + 10-year cap rules live in
 * {@code IsvaAccountService.renew} so they apply identically when the
 * service is exercised via tests, scripts, or future integration
 * paths — keeping the controller dumb avoids drift between layers.
 *
 * @param validUntil the new {@code secValidUntil} value, must be non-null
 */
public record RenewRequest(@NotNull OffsetDateTime validUntil) {
}
