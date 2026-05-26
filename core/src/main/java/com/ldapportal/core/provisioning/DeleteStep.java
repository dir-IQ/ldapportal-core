// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

/**
 * {@link LdapOperationStep} that removes an LDAP entry. Translates to
 * an UnboundID {@code delete(targetDn)} call when {@link PlanExecutor}
 * runs it.
 */
public record DeleteStep(
        String targetDn,
        StepFailurePolicy onFailure) implements LdapOperationStep {

    /**
     * Convenience: DELETE with the default ABORT failure policy.
     */
    public static DeleteStep of(String targetDn) {
        return new DeleteStep(targetDn, StepFailurePolicy.ABORT);
    }
}
