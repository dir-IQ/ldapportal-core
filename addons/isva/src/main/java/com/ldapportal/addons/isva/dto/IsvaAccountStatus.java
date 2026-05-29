// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.dto;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;

import java.time.OffsetDateTime;

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
 * caller computing {@code !linked}. The compact constructor enforces
 * the invariant so a hand-rolled record (vs. via the factories) can't
 * silently land in a broken state.</p>
 *
 * <p>{@code secUserDn} is null in {@link IsvaTopologyMode#INLINE} —
 * inline mode keeps the IVIA overlay on the demographic entry,
 * so there's no separate secUser DN to surface.</p>
 *
 * <p>For an orphaned identity (no IVIA account present), every
 * IVIA-side attribute is null/false; only {@code topology} is
 * meaningful. Callers should branch on {@code orphaned} before
 * reading the lifecycle fields.</p>
 *
 * <p><strong>Days-remaining is intentionally not on the snapshot.</strong>
 * It's a derivation of {@code validUntil} relative to "now", which
 * has different correct answers on the server (whose clock can drift
 * from the user's by minutes/hours) vs. on the client. The frontend
 * computes it locally from {@code validUntil} so the displayed value
 * always reflects the user's wall-clock — no server/client clock skew
 * surfacing as a UI inconsistency.</p>
 */
public record IsvaAccountStatus(
        boolean linked,
        boolean orphaned,
        IsvaTopologyMode topology,
        boolean acctValid,
        OffsetDateTime validUntil,
        boolean pwdValid,
        OffsetDateTime pwdLastChanged,
        String authority,
        String secUserDn) {

    public IsvaAccountStatus {
        if (linked == orphaned) {
            throw new IllegalArgumentException(
                    "linked and orphaned must be inverses (linked=" + linked
                            + ", orphaned=" + orphaned + ")");
        }
    }

    public static IsvaAccountStatus orphaned(IsvaTopologyMode topology) {
        return new IsvaAccountStatus(
                false, true, topology,
                false, null,
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
        return new IsvaAccountStatus(
                true, false, topology,
                acctValid, validUntil,
                pwdValid, pwdLastChanged,
                authority, secUserDn);
    }

    // ── copy-with helpers ────────────────────────────────────────────
    //
    // Used by IsvaAccountService to derive post-write snapshots
    // without re-probing LDAP. The verbs know exactly what fields
    // their write changed; one of these lets them flip just that
    // field while preserving every other.

    public IsvaAccountStatus withAcctValid(boolean v) {
        return new IsvaAccountStatus(
                linked, orphaned, topology, v, validUntil,
                pwdValid, pwdLastChanged, authority, secUserDn);
    }

    public IsvaAccountStatus withValidUntil(OffsetDateTime v) {
        return new IsvaAccountStatus(
                linked, orphaned, topology, acctValid, v,
                pwdValid, pwdLastChanged, authority, secUserDn);
    }

    public IsvaAccountStatus withPwdValid(boolean v) {
        return new IsvaAccountStatus(
                linked, orphaned, topology, acctValid, validUntil,
                v, pwdLastChanged, authority, secUserDn);
    }
}
