// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import java.util.List;

/**
 * Ordered list of LDAP operations that together implement one
 * logical user-delete. Single-step DEL in the default case.
 * Linked-mode soft-disable produces a single MODIFY against the
 * secUser DN; linked-mode hard-delete produces two DELs (secUser
 * first so a partial failure leaves a recoverable demographic).
 *
 * <p>No {@code compensation} field — deletes that can't atomically
 * undo just fail the second step with a "manual repair needed"
 * annotation. Atomic LDAP doesn't exist; pretending otherwise
 * would create more bugs than it solves.</p>
 */
public record DeletePlan(List<LdapOperationStep> steps) {

    public DeletePlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static DeletePlan singleStep(LdapOperationStep step) {
        return new DeletePlan(List.of(step));
    }
}
