// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Snapshot of an identity's IVIA-side account state, returned by
 * {@code IsvaAccountStatusProbe} and surfaced verbatim by the
 * {@code IsvaAccountStatusDto} (P2). The probe is the single source
 * of truth for "is there an IVIA account, and what shape is it in";
 * verbs in {@code IsvaAccountService} consult the same snapshot for
 * their re-verify guards.
 *
 * <p>{@code linked} and {@code orphaned} are inverses by design —
 * exposed as separate fields because the UI panel binds to both
 * predicates and pre-deriving the inverse is cheaper than every
 * caller computing {@code !linked}.</p>
 *
 * <p>{@code secUserDn} is null in {@link IsvaTopologyMode#INLINE} —
 * inline mode keeps the IVIA overlay on the demographic entry,
 * so there's no separate secUser DN to surface.</p>
 *
 * <p>For an orphaned identity (no IVIA account present), every
 * IVIA-side attribute is null/false; only {@code topology} is
 * meaningful. Callers should branch on {@code orphaned} before
 * reading the lifecycle fields.</p>
 */
public record IsvaAccountStatus(
        boolean linked,
        boolean orphaned,
        IsvaTopologyMode topology,
        boolean acctValid,
        OffsetDateTime validUntil,
        Integer daysRemaining,
        boolean pwdValid,
        OffsetDateTime pwdLastChanged,
        String authority,
        String secUserDn) {

    public static IsvaAccountStatus orphaned(IsvaTopologyMode topology) {
        return new IsvaAccountStatus(
                false, true, topology,
                false, null, null,
                false, null,
                null, null);
    }

    public static IsvaAccountStatus present(IsvaTopologyMode topology,
                                            String secUserDn,
                                            boolean acctValid,
                                            OffsetDateTime validUntil,
                                            boolean pwdValid,
                                            OffsetDateTime pwdLastChanged,
                                            String authority) {
        // Both sides must be in the same offset for DAYS.between's
        // LocalDate-boundary walk to give a stable result. validUntil
        // comes from LDAP as UTC; normalise now() to UTC to match.
        // Without this, a Chicago-zoned container near a UTC-midnight
        // boundary reports a count that's one off from a UTC-zoned
        // container at the same instant.
        Integer daysRemaining = validUntil == null
                ? null
                : (int) ChronoUnit.DAYS.between(OffsetDateTime.now(ZoneOffset.UTC), validUntil);
        return new IsvaAccountStatus(
                true, false, topology,
                acctValid, validUntil, daysRemaining,
                pwdValid, pwdLastChanged,
                authority, secUserDn);
    }
}
