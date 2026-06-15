// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Customer license. Carries edition, purchased add-ons, and per-resource
 * limits. Phase 6 populates this from a signed JWT; Phase 1's
 * {@link DefaultEntitlementService} synthesizes one from
 * {@code ApplicationSettings} to preserve existing behaviour without a
 * license file.
 *
 * <p>{@code signature} is the Ed25519 signature of the encoded license
 * payload. It is {@code null} for the Phase 1 default-provider license
 * (no signing until Phase 6). The field is in the record now so that
 * Phase 6's file-based provider can populate it without a schema change.</p>
 *
 * <p>{@code expiresAt} being {@link Instant#MAX} means "never expires" —
 * used by the community-build hardcoded license. Real signed licenses
 * always carry a real expiry.</p>
 */
public record License(
        /** Opaque customer identifier; null in default/community mode. */
        UUID customerId,
        Edition edition,
        /** Entitlements purchased on top of the edition baseline. */
        Set<Entitlement> addOns,
        /** Per-resource quotas. Missing key = unlimited. */
        Map<LimitType, Long> limits,
        Instant issuedAt,
        Instant expiresAt,
        /** Ed25519 signature; null in Phase 1. */
        String signature) {

    public License {
        // Defensive copies — records are nominally immutable but the
        // collection fields are references; copy on construction to prevent
        // external mutation reaching the License.
        addOns = addOns == null ? Set.of() : Set.copyOf(addOns);
        limits = limits == null ? Map.of() : Map.copyOf(limits);
    }

    /**
     * True if this license grants the given entitlement — either through
     * its edition baseline or as a separately-purchased add-on.
     */
    public boolean has(Entitlement e) {
        return edition.baselineEntitlements().contains(e) || addOns.contains(e);
    }

    /**
     * Quota for the given resource, or {@link Long#MAX_VALUE} when unlimited
     * (default when the limit key is absent from the license).
     */
    public long limitFor(LimitType t) {
        return limits.getOrDefault(t, Long.MAX_VALUE);
    }

    /**
     * True if the license has passed its expiry instant. The community-build
     * license uses {@link Instant#MAX} and is never expired.
     */
    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
