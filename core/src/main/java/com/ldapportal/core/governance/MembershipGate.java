// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.governance;

import com.ldapportal.auth.AuthPrincipal;

import java.util.UUID;

/**
 * Synchronous pre-commit gate for group membership changes.
 *
 * <p>Called by {@code LdapOperationService} immediately before a group
 * membership is modified. Implementations may throw to block the change
 * — typically a {@code SodViolationException} when a Separation-of-Duties
 * policy would be violated.</p>
 *
 * <p>Core ships with a permissive default ({@link NoopMembershipGate})
 * that allows every change. The real enforcement lives in
 * {@code ee.governance.SodPolicyService}, which registers itself as the
 * bean when the governance module is on the classpath; the default is
 * suppressed via {@link org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean}.
 * Community editions get the permissive default with no SoD module loaded
 * — which is correct, since community doesn't offer SoD.</p>
 */
public interface MembershipGate {

    /**
     * Checks whether the given user can be added to the given group.
     *
     * @throws RuntimeException (typically {@code SodViolationException}) if
     *         the change would violate a policy and should be blocked.
     */
    void checkMembership(UUID directoryId, String userDn, String groupDn, AuthPrincipal principal);
}
