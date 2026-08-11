// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import java.util.List;

/**
 * Ordered list of LDAP operations that together implement one
 * logical account enable / disable.
 *
 * <p>Baseline (and inline ISVA) is a single MODIFY replacing the
 * directory's configured enable/disable attribute on the user entry.
 * Linked-mode ISVA produces two MODIFYs: the enable/disable attribute
 * on the demographic DN, plus a {@code secAcctValid} flip on the paired
 * secUser DN — so disabling (or re-enabling) the demographic entry
 * mirrors onto its ISVA account rather than leaving the two out of
 * step.</p>
 *
 * <p>No compensation block — an enable/disable is idempotent and
 * reversible, so a partial failure is safe to simply retry.</p>
 */
public record EnableDisablePlan(List<LdapOperationStep> steps) {

    public EnableDisablePlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static EnableDisablePlan singleStep(LdapOperationStep step) {
        return new EnableDisablePlan(List.of(step));
    }
}
