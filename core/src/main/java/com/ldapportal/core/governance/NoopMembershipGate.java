// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.governance;

import com.ldapportal.auth.AuthPrincipal;

import java.util.UUID;

/**
 * Default {@link MembershipGate} that allows every membership change.
 * Registered via {@link com.ldapportal.core.CoreNoopSpiAutoConfiguration}
 * when no other {@code MembershipGate} bean is present — i.e. when the
 * ee.governance module isn't loaded (community edition or governance
 * not entitled).
 *
 * <p>Rationale: the gate fires on the hot path of every group-membership
 * mutation. When governance is unavailable, the gate must be an
 * unconditional pass-through, not throw "feature not licensed" — the
 * underlying membership operation has nothing to do with governance
 * entitlement, it just happened to run through a check point.</p>
 */
public class NoopMembershipGate implements MembershipGate {

    @Override
    public void checkMembership(UUID directoryId, String userDn, String groupDn, AuthPrincipal principal) {
        // intentionally empty — no SoD module, no gate
    }
}
