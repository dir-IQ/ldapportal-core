// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import java.util.List;
import java.util.Optional;

/**
 * Ordered list of LDAP operations that together implement one
 * logical user-create. Single-step in inline mode (one ADD against
 * the user DN). Two steps in linked mode (demographic ADD first,
 * secUser ADD second, the second step's failure policy =
 * COMPENSATE and the {@code compensation} block deletes the
 * demographic entry).
 *
 * <p>{@code compensation} is run only when a step with
 * {@link StepFailurePolicy#COMPENSATE} fails — best-effort cleanup
 * of completed earlier steps. The original exception is what gets
 * propagated; the compensation outcome is annotated on it.</p>
 */
public record UserCreatePlan(
        List<LdapOperationStep> steps,
        Optional<List<LdapOperationStep>> compensation) {

    public UserCreatePlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
        // Optional itself is immutable; copy the inner list defensively.
        compensation = compensation == null
                ? Optional.empty()
                : compensation.map(List::copyOf);
    }

    /** Convenience: single-step plan, no compensation. */
    public static UserCreatePlan singleStep(LdapOperationStep step) {
        return new UserCreatePlan(List.of(step), Optional.empty());
    }
}
