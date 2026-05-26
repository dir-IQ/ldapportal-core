// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import com.unboundid.ldap.sdk.Modification;

import java.util.List;

/**
 * {@link LdapOperationStep} that mutates an existing LDAP entry.
 * Translates to an UnboundID {@code ModifyRequest(targetDn, mods)}
 * when {@link PlanExecutor} runs it.
 */
public record ModifyStep(
        String targetDn,
        List<Modification> mods,
        StepFailurePolicy onFailure) implements LdapOperationStep {

    public ModifyStep {
        mods = mods == null ? List.of() : List.copyOf(mods);
    }

    /**
     * Convenience: MODIFY with the default ABORT failure policy.
     */
    public static ModifyStep of(String targetDn, List<Modification> mods) {
        return new ModifyStep(targetDn, mods, StepFailurePolicy.ABORT);
    }
}
