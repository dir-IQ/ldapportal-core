// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

/**
 * {@link LdapOperationStep} that removes an LDAP entry. Translates to
 * an UnboundID {@code delete(targetDn)} call when {@link PlanExecutor}
 * runs it.
 *
 * <p>When {@code subtree} is set, {@link PlanExecutor} first removes
 * every entry beneath {@code targetDn} (deepest first) and then the
 * target itself. Use it for entries that a directory product may
 * decorate with server-managed children — e.g. IVIA records per-user
 * password policy in a {@code cn=policies} entry under the secUser —
 * where a plain DEL would fail with {@code notAllowedOnNonLeaf}.</p>
 */
public record DeleteStep(
        String targetDn,
        StepFailurePolicy onFailure,
        boolean subtree) implements LdapOperationStep {

    /** Leaf-only DELETE (the target must have no children). */
    public DeleteStep(String targetDn, StepFailurePolicy onFailure) {
        this(targetDn, onFailure, false);
    }

    /**
     * Convenience: leaf-only DELETE with the default ABORT failure policy.
     */
    public static DeleteStep of(String targetDn) {
        return new DeleteStep(targetDn, StepFailurePolicy.ABORT, false);
    }

    /**
     * Convenience: DELETE of the target and everything beneath it, with
     * the default ABORT failure policy.
     */
    public static DeleteStep subtree(String targetDn) {
        return new DeleteStep(targetDn, StepFailurePolicy.ABORT, true);
    }
}
