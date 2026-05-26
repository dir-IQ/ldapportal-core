// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.provisioning;

import java.util.List;

/**
 * Ordered list of LDAP operations that together implement one
 * logical password set. Single-step MODIFY in inline / baseline
 * mode (either {@code userPassword} or, for AD, the UTF-16LE-
 * encoded {@code unicodePwd}). Linked mode produces two MODIFYs:
 * {@code userPassword} on the demographic DN, {@code secPwdLastChanged}
 * on the secUser DN.
 */
public record PasswordPlan(List<LdapOperationStep> steps) {

    public PasswordPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public static PasswordPlan singleStep(LdapOperationStep step) {
        return new PasswordPlan(List.of(step));
    }
}
