// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.license;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only snapshot of the current license, intended for the admin
 * license-status view. Exposes only what the superadmin legitimately
 * needs to see — the raw JWT stays server-side.
 *
 * <p>Nullable fields reflect the two provider shapes:</p>
 * <ul>
 *   <li>{@code FileLicenseProvider} — everything populated; the license
 *       is a real, signed assertion with a real expiration.</li>
 *   <li>{@code CommunityEditionLicenseProvider} — the no-license
 *       fallback (community baseline). No customer id, no real
 *       {@code issuedAt}, no expiration, and {@code signed} is
 *       {@code false}. The UI should special-case this: "no signed
 *       license is installed; running with community baseline
 *       entitlements."</li>
 * </ul>
 */
public record LicenseStatusDto(
        /** Edition name: COMMUNITY / TEAM / BUSINESS / ENTERPRISE. */
        String edition,

        /** Customer UUID as string; null for the community baseline (no license). */
        String customerId,

        /** True if the license is cryptographically signed (i.e. from a file). */
        boolean signed,

        /** Add-on entitlements declared by the license (on top of edition baseline). */
        List<String> addOns,

        /** Everything the license actually grants (baseline ∪ addOns). */
        List<String> grantedEntitlements,

        /** Entitlements this edition/license does not cover. */
        List<String> withheldEntitlements,

        /** Resource limits, e.g. {@code {DIRECTORIES: 25}}. Empty means unlimited. */
        Map<String, Long> limits,

        /** When the license was issued; null for the community baseline. */
        OffsetDateTime issuedAt,

        /** When the license expires; null for the community baseline (never expires). */
        OffsetDateTime expiresAt,

        /** Whole days until expiry. Negative if past-expiry. Null if never-expires. */
        Long daysRemaining,

        /**
         * Overall expiry status:
         * <ul>
         *   <li>{@code NO_EXPIRY} — community baseline; never expires.</li>
         *   <li>{@code VALID} — not near expiry.</li>
         *   <li>{@code APPROACHING_EXPIRY} — under 30 days until expiry.</li>
         *   <li>{@code EXPIRED_WITHIN_GRACE} — past {@code expiresAt} but within grace.</li>
         *   <li>{@code PAST_GRACE} — past {@code expiresAt + graceDays}.</li>
         * </ul>
         */
        String graceState,

        /** Grace period length in days (informational; only meaningful with expiry). */
        Long graceDays,

        /** One-liner describing where the license came from (path or "application settings"). */
        String source) {
}
